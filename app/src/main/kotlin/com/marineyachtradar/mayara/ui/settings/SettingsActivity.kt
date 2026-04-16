package com.marineyachtradar.mayara.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.marineyachtradar.mayara.ui.theme.MayaraTheme

/**
 * Full-screen Settings Activity (spec §3.5).
 *
 * Kept separate from [MainActivity] so it has its own back stack and the radar
 * view remains alive while settings are open.
 *
 * TODO Phase 5: implement the full Compose Navigation graph with:
 *   - ConnectionSettingsScreen
 *   - EmbeddedServerLogsScreen
 *   - UnitsScreen
 *   - AppInfoScreen
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MayaraTheme {
                // TODO Phase 5: SettingsNavHost()
            }
        }
    }
}
