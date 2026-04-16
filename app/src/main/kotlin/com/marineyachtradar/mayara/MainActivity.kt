package com.marineyachtradar.mayara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.marineyachtradar.mayara.ui.radar.RadarScreen
import com.marineyachtradar.mayara.ui.theme.MayaraTheme

/**
 * Single activity that hosts the full Compose navigation graph.
 *
 * The radar display ([RadarScreen]) is fullscreen landscape and keeps the screen on.
 * Navigation to [SettingsActivity] is handled by an explicit Intent (separate back stack).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MayaraTheme {
                RadarScreen()
            }
        }
    }
}
