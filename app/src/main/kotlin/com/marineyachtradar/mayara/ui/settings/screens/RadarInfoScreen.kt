package com.marineyachtradar.mayara.ui.settings.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marineyachtradar.mayara.domain.RadarInfoSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarInfoScreen(
    radarInfo: RadarInfoSnapshot? = null,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Radar Info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            if (radarInfo != null) {
                item {
                    ListItem(
                        headlineContent = { Text("Name") },
                        trailingContent = { Text(radarInfo.radarName) },
                    )
                    HorizontalDivider()
                }
                item {
                    ListItem(
                        headlineContent = { Text("Brand") },
                        trailingContent = { Text(radarInfo.brand) },
                    )
                    HorizontalDivider()
                }
                radarInfo.modelName?.let { model ->
                    item {
                        ListItem(
                            headlineContent = { Text("Model") },
                            trailingContent = { Text(model) },
                        )
                        HorizontalDivider()
                    }
                }
                radarInfo.serialNumber?.let { serial ->
                    item {
                        ListItem(
                            headlineContent = { Text("Serial Number") },
                            trailingContent = { Text(serial) },
                        )
                        HorizontalDivider()
                    }
                }
                radarInfo.firmwareVersion?.let { fw ->
                    item {
                        ListItem(
                            headlineContent = { Text("Firmware") },
                            trailingContent = { Text(fw) },
                        )
                        HorizontalDivider()
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text("Spokes / Revolution") },
                        trailingContent = { Text("${radarInfo.spokesPerRevolution}") },
                    )
                    HorizontalDivider()
                }
                item {
                    ListItem(
                        headlineContent = { Text("Max Spoke Length") },
                        trailingContent = { Text("${radarInfo.maxSpokeLength} samples") },
                    )
                    HorizontalDivider()
                }
                item {
                    ListItem(
                        headlineContent = { Text("Operating Time") },
                        trailingContent = {
                            Text(
                                radarInfo.operatingTimeSeconds?.let { formatHours(it) }
                                    ?: "N/A"
                            )
                        },
                    )
                    HorizontalDivider()
                }
                item {
                    ListItem(
                        headlineContent = { Text("Transmit Time") },
                        trailingContent = {
                            Text(
                                radarInfo.transmitTimeSeconds?.let { formatHours(it) }
                                    ?: "N/A"
                            )
                        },
                    )
                }
            } else {
                item {
                    ListItem(
                        headlineContent = { Text("Not connected") },
                        supportingContent = { Text("Connect to a radar to see details") },
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private fun formatHours(seconds: Float): String {
    val hours = seconds / 3600f
    return if (hours >= 1f) {
        "%.1f h".format(hours)
    } else {
        "%.0f min".format(seconds / 60f)
    }
}
