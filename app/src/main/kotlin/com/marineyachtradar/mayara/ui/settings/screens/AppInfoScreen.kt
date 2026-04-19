package com.marineyachtradar.mayara.ui.settings.screens

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.marineyachtradar.mayara.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoScreen(
    appVersion: String,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Info") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── App Icon ──
            item {
                Spacer(Modifier.height(16.dp))
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = "Mayara App Icon",
                    modifier = Modifier.size(80.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Mayara",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Marine Radar Display",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }

            // ── App Section ──
            item {
                SectionHeader("Application")
            }
            item {
                ListItem(
                    headlineContent = { Text("Version") },
                    trailingContent = { Text(appVersion) },
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text("Device") },
                    trailingContent = { Text("${Build.MANUFACTURER} ${Build.MODEL}") },
                )
                HorizontalDivider()
            }

            // ── License Section ──
            item {
                SectionHeader("Licenses")
            }
            item {
                ListItem(
                    headlineContent = { Text("Mayara App") },
                    trailingContent = { Text("GPL-2.0") },
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text("mayara-server") },
                    trailingContent = { Text("GPL-2.0") },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
