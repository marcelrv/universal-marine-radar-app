package com.marineyachtradar.mayara.ui.smoke

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marineyachtradar.mayara.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test that verifies the app launches without crashing.
 *
 * This is the first Compose instrumented test. It runs on an actual Android device
 * or emulator (via the android-emulator-runner GitHub Actions job).
 *
 * On launch, the app will show either:
 *   - The connection picker dialog (first-run, no saved preference), or
 *   - The radar screen (if a connection preference is saved).
 *
 * Either way, the root Compose node must be present and displayed.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesWithoutCrash() {
        // The root Compose node must exist — proves the Activity set up its Compose content
        composeRule.onRoot().assertIsDisplayed()
    }
}
