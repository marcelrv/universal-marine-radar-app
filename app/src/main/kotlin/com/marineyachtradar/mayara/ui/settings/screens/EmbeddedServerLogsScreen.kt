package com.marineyachtradar.mayara.ui.settings.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Minimum log severity to display. */
enum class LogLevel(val label: String, val prefix: String?) {
    ALL("All", null),
    INFO("Info", "[INFO]"),
    WARN("Warn", "[WARN]"),
    ERROR("Error", "[ERROR]"),
}

/**
 * Embedded Server Logs screen (spec §3.5 — Embedded Server Status).
 *
 * Auto-refreshes every 2 seconds via [LaunchedEffect]. The scroll state is maintained
 * in the composable so new lines appear at the bottom. Log text is wrapped in
 * [SelectionContainer] so individual lines can be long-pressed and copied.
 *
 * @param logs      Current log output from [com.marineyachtradar.mayara.jni.RadarJni.getLogs].
 * @param onRefresh Called to fetch the latest logs (hoisted to [SettingsViewModel.refreshLogs]).
 * @param onBack    Called when the user presses the back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbeddedServerLogsScreen(
    logs: String,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    // Auto-refresh every 2 seconds while the screen is active
    LaunchedEffect(Unit) {
        while (isActive) {
            onRefresh()
            delay(2_000)
        }
    }

    var selectedLevel by remember { mutableStateOf(LogLevel.ALL) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    // Log-level filter dropdown
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = it },
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        OutlinedTextField(
                            value = selectedLevel.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Level") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                            },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .padding(vertical = 4.dp),
                            singleLine = true,
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                        ) {
                            LogLevel.entries.forEach { level ->
                                DropdownMenuItem(
                                    text = { Text(level.label) },
                                    onClick = {
                                        selectedLevel = level
                                        dropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh logs",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        val allLines = logs.lines()
        // When filtering by level, keep continuation lines (stack traces, etc.)
        // that belong to a matching parent entry (lines without a level prefix).
        val lines = when (selectedLevel) {
            LogLevel.ALL -> allLines
            else -> {
                var inMatchingGroup = false
                allLines.filter { line ->
                    val isLevelLine = line.startsWith("[INFO]") || line.startsWith("[WARN]") || line.startsWith("[ERROR]")
                    if (isLevelLine) {
                        inMatchingGroup = line.startsWith(selectedLevel.prefix!!)
                    }
                    inMatchingGroup
                }
            }
        }

        val emptyMessage = when {
            logs.isEmpty() -> "No logs available"
            lines.isEmpty() -> "No ${selectedLevel.label} entries"
            else -> null
        }

        if (emptyMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val listState = rememberLazyListState()

            // Scroll to the last line when new logs arrive
            LaunchedEffect(lines.size) {
                if (lines.isNotEmpty()) {
                    listState.animateScrollToItem(lines.lastIndex)
                }
            }

            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = logLineColor(line),
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Colour-code log lines by severity prefix. */
@Composable
private fun logLineColor(line: String) = when {
    line.startsWith("[ERROR]") -> MaterialTheme.colorScheme.error
    line.startsWith("[WARN]") -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}
