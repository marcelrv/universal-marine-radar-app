package com.marineyachtradar.mayara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marineyachtradar.mayara.data.model.ConnectionMode
import com.marineyachtradar.mayara.ui.radar.RadarScreen
import com.marineyachtradar.mayara.ui.radar.RadarViewModel
import com.marineyachtradar.mayara.ui.theme.MayaraTheme

/**
 * Single activity that hosts the full Compose navigation graph.
 *
 * Accepts an optional `pcap_path` String extra (and `pcap_repeat` Boolean) to trigger
 * PCAP demo mode directly — useful for ADB testing:
 *   adb shell am start -n com.marineyachtradar.mayara/.MainActivity \
 *     --es pcap_path /sdcard/Download/radar11.pcap --ez pcap_repeat true
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Read optional ADB/test extras for PCAP demo mode
        val pcapPath = intent.getStringExtra("pcap_path")
        val pcapRepeat = intent.getBooleanExtra("pcap_repeat", true)

        setContent {
            MayaraTheme {
                val vm: RadarViewModel = viewModel()
                // If a pcap_path was passed in the launch intent, connect automatically
                if (pcapPath != null && savedInstanceState == null) {
                    androidx.compose.runtime.LaunchedEffect(pcapPath) {
                        vm.onConnect(ConnectionMode.PcapDemo(pcapPath = pcapPath, repeat = pcapRepeat), remember = false)
                    }
                }
                RadarScreen(viewModel = vm)
            }
        }
    }
}
