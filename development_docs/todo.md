# Todo: Mayara Android App

## Legend
- `[ ]` Not started
- `[~]` In progress
- `[x]` Done
- `[!]` Blocked — human action required

---

## Phase 0 — Scaffolding & Docs ✅ COMPLETE

- [x] Create `development_docs/project_plan.md`
- [x] Create `development_docs/testing_plan.md`
- [x] Create `development_docs/todo.md` (this file)
- [x] Create `development_docs/folder_structure.md`
- [x] Create `development_docs/agent_instructions.md`
- [x] Create root `AGENTS.md`
- [x] Create `.gitmodules` template
- [x] Scaffold Android Gradle project (`settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`)
- [x] Scaffold `app/build.gradle.kts` and `AndroidManifest.xml`
- [x] Scaffold `mayara-jni/Cargo.toml` + `src/lib.rs`
- [x] Create `scripts/build_jni.sh`, `scripts/update_mayara.sh`, `scripts/copy_so.sh`
- [x] Create `.github/workflows/ci.yml`
- [x] Create Kotlin skeleton files: `MainActivity`, `RadarJni`, `RadarModels`, `RadarScreen`,
      `PowerToggle`, `RangeControls`, `HudOverlay`, `MayaraTheme`, `ConnectionManager`,
      `SettingsActivity`, `proguard-rules.pro`, `.gitignore`
- [!] **HUMAN**: Fork `https://github.com/MarineYachtRadar/mayara-server` on GitHub
- [!] **HUMAN**: `git submodule add <fork-url> mayara-server` then update `.gitmodules` with fork URL
- [!] **HUMAN**: `git submodule update --init --recursive`
- [!] **HUMAN**: Install Rust Android target: `rustup target add aarch64-linux-android`
- [!] **HUMAN**: Install cargo-ndk: `cargo install cargo-ndk`
- [!] **HUMAN**: Install Android NDK r26b via Android Studio SDK Manager → SDK Tools → NDK
- [!] **HUMAN**: Set `ANDROID_NDK_HOME` environment variable
- [!] **HUMAN**: Open `mayara-app/` root in Android Studio, sync Gradle

---

## Phase 1 — mayara-server Fork: Android JNI Target  ← NEXT UP

- [x] Create `mayara-jni/Cargo.toml` with correct crate-type = ["cdylib"]
- [x] Create `mayara-jni/src/lib.rs` with JNI function stubs and Tokio runtime management
- [x] Create `app/.../jni/RadarJni.kt` with `external fun` declarations
- [x] **Fork modification – Step 1**: Add `crate-type = ["rlib"]` to `mayara-server/Cargo.toml`
- [x] **Fork modification – Step 2**: Gate `terminal_size` dependency: moved to `[target.'cfg(not(target_os = "android"))'.dependencies]`
- [x] **Fork modification – Step 3**: Move `src/bin/mayara-server/web.rs` + sub-modules into `src/lib/server/`
- [x] **Fork modification – Step 4**: Expose `pub async fn start_android(cli: Cli, shutdown_rx: oneshot::Receiver<()>)` in `src/lib/server/mod.rs`
- [x] **Fork modification – Step 5**: Keep binary thin — `src/bin/mayara-server/web.rs` is now a 7-line re-export shim
- [x] Upgrade `mayara-jni/src/lib.rs` `run_server()` to call `mayara::server::start_android(cli, shutdown_rx)`
- [x] Add `cargo test` for JNI lifecycle to `mayara-jni/src/lib.rs`
- [ ] Verify `scripts/build_jni.sh` exits 0 and produces `libradar.so` ≥ 1 MB
  - **Note:** Windows cross-compile requires Perl (openssl-sys build dependency). Use WSL or Linux.
  - **On Linux/macOS:** `bash scripts/build_jni.sh` will work directly
- [!] **HUMAN**: Run `scripts/build_jni.sh` (on Linux/macOS or WSL) and confirm `app/src/main/jniLibs/arm64-v8a/libradar.so` exists
- [ ] Verify on-device: `System.loadLibrary("radar")` does not throw `UnsatisfiedLinkError`

---

## Phase 2 — Network Client & Data Layer ✅ COMPLETE

- [x] Add `wire-gradle-plugin` to `app/build.gradle.kts`; configure `.proto` source from submodule path
- [x] Verify protobuf codegen produces `RadarMessage` Kotlin class (pkg `com.marineyachtradar.mayara.proto`)
- [x] Write `RadarApiClient.kt`: `getRadars()`, `getCapabilities(id)`, `putControl(id, cid, value)`
- [x] Write `SpokeWebSocketClient.kt`: WS connect, binary proto decode, `Flow<SpokeData>`
- [x] Write `MdnsScanner.kt`: `NsdManager` scan for `_signalk-ws._tcp` and `_mayara._tcp`
- [x] Write `ConnectionManager.kt`: `ConnectionMode` sealed class, persist via DataStore
- [x] Write `RadarRepository.kt`: `StateFlow<RadarUiState>` aggregating REST + WS + JNI state
- [x] Write `CapabilitiesMapper.kt`: maps capabilities JSON to `List<ControlDefinition>`
- [x] Write JVM unit tests for all data-layer classes (Layer 3 in testing plan)
- [x] `./gradlew test` passes all data-layer tests — **44 tests, 0 failures**

---

## Phase 3 — OpenGL ES Renderer ✅ COMPLETE

- [x] Write `RadarGLView.kt`: `AndroidView` wrapping `GLSurfaceView`
- [x] Write `RadarGLRenderer.kt`: 512×512 `GL_LUMINANCE` texture allocated on startup
- [x] Implement spoke-to-texture: `angle` → column, `data` bytes → row luminance
- [x] Write GLSL vertex and fragment shaders with palette LUT uniform
- [x] Implement palette switching: Green / Yellow / Multicolor / Night-Red
- [x] Implement pan gesture (1-finger drag → `centerOffset` uniforms)
- [x] Implement double-tap reset (`centerOffset` → 0)
- [x] Write pinch gesture consumer that discards zoom events (safety-critical)
- [x] Write gesture unit tests: `PinchZoomDisabledTest` (3 tests)
- [x] Write texture buffer unit tests: `RadarTextureBufferTest` (4 tests)
- [x] Wire `RadarGLView` into `RadarScreen` as Layer 0 (feeds spoke data + palette)
- [x] Fix CLEARTEXT HTTP policy for 127.0.0.1 / 10.0.2.2 (`network_security_config.xml`)
- [x] `./gradlew test` passes — 25 tasks, exit 0
- [x] `./gradlew assembleDebug` succeeds — APK built

---

## Phase 4 — Compose UI Overlays ✅ COMPLETE

- [x] Write `PowerToggle.kt`: OFF/WARMUP/STANDBY/TRANSMIT pill button with countdown
- [x] Write `RangeControls.kt`: +/- FABs, monospace range text, stepping from capabilities array
- [x] Write `HudOverlay.kt`: heading/SOG/COG, hidden when null
- [x] Write `RadarControlSheet.kt`: bottom sheet with Gain/Sea/Rain/IR sliders + Auto toggles + Palette + Orientation
- [x] Write `ConnectionPickerDialog.kt`: modal with Embedded/Network choice, "Remember" checkbox
- [x] Write `RadarScreen.kt`: compose all layers in a `Box`
- [x] Compose component tests for each overlay (Layer 4a) — 44 new tests (PowerToggleStateTest, RangeFormatTest, ControlSheetDisplayNameTest, ConnectionPickerStateTest + 5 new RadarRepositoryTest)
- [ ] Paparazzi screenshots for day + night + minimal-capabilities variants
- [ ] Visual review of screenshots (agent-generated description + image diff)

---

## Phase 5 — Settings Activity ✅ COMPLETE

- [x] Write `SettingsActivity.kt` with Compose Navigation host
- [x] Write `ConnectionSettingsScreen.kt`: status, Switch button, manual IP/port
- [x] Write `EmbeddedServerLogsScreen.kt`: polling `RadarJni.getLogs()` every 2 s
- [x] Write `UnitsScreen.kt`: distance (NM/KM/SM) and bearing (True/Magnetic) with DataStore
- [x] Write `AppInfoScreen.kt`: version, license, radar firmware (from capabilities)
- [x] Settings navigation unit tests
- [x] Hamburger/Gear icon in `RadarScreen` navigates to Settings correctly

---

## Phase 6 — Integration, CI, Polish ✅ COMPLETE (2025-04-17)

- [x] Write integration test `IntegrationEmbeddedModeTest.kt` (Layer 5 in testing plan) — 4 tests passing
- [x] Write integration test `IntegrationControlRoundTripTest.kt` — 3 tests passing
- [x] Wire full CI pipeline in `.github/workflows/ci.yml` — `timeout-minutes: 30` added to instrumented job
- [ ] Enable Paparazzi screenshot diffing in CI
- [x] Night-vision Red/Black mode toggle wired to DataStore preference (UnitsPreferences palette)
- [x] App icon and branding — custom radar-themed foreground/background drawables
- [x] ProGuard/R8 rules for Rust JNI symbols, DataStore, Coroutines, Lifecycle, Navigation
- [x] Performance benchmarks (Layer 6 in testing plan) — `PerformanceBenchmarkTest.kt`, 3 tests
- [x] Emulator validation — Medium_Phone_API_35, app renders correctly (connection error expected: libradar.so is arm64-only)
- [x] 137 JVM unit tests + 1 instrumented test — 0 failures
- [!] **HUMAN**: Manual testing checklist from `testing_plan.md`
- [!] **HUMAN**: Test on physical Navico/Furuno/Raymarine radar if available

---

## Bugfixes

See `development_docs/bugfix_spec.md` for root-cause analysis, fix specifications, and test plans.

- [x] **BF-01** Range display does not update when the server reports a range change via the SignalK control stream
- [x] **BF-02** No range rings are drawn on the radar display
- [x] **BF-03** Range label uses a single decimal (e.g. `1.5 NM`) instead of marine fraction notation (e.g. `1/8 NM`) for short ranges; unit preference (NM/KM/SM) is ignored
- [x] **BF-04** No connection-type indicator on main screen (embedded vs. network, server address)
- [x] **BF-05** Radar name is not displayed on main screen; multi-radar switching UI is missing
- [x] **BF-06** UI controls are partially hidden behind Android system bars because window inset padding is not applied to overlay composables
- [x] **BF-07** Power state always shows `OFF` on initial connect regardless of actual radar state (defaults to 0 when `power` control value is absent or unparseable)
- [x] **BF-08** On orientation change (landscape ↔ portrait) the radar circle is over-sized or distorted because the `u_Resolution` uniform is not updated when the viewport dimensions change
- [x] **BF-09** Radar spoke image is inverted and rotated 180°: fragment shader `v`-coordinate flip maps screen centre to max-range data, and the `+0.5` angle offset rotates the image 180°
- [x] **BF-10** Embedded server log screen shows only JNI lifecycle messages; mayara-server internal log output is not routed to the in-app log buffer
- [x] **BF-11** Radar texture is never cleared: stale spokes persist indefinitely, making the display appear frozen when transmission stops
- [x] **BF-12** mDNS network scanner (`MdnsScanner`) is implemented but not wired to the connection picker dialog



Improvements
- [x] in portrait scale down so the whole circle is visible
- [x] add units at the range circles
- [x] the range plus/minus needs to jump only through the units that are based on the selected unit of measure, so in the KM, it goes through the km ranges, in NM it goes through the NM ranges
- [x] the name of the radar can be in a pill
- [x] add lines for the compas and indicate the direction similar to the range units
- [ ] in portrait mode, if the size is wide enough put the visual controls (gain sea clutter etc) on the right side of the screen, moving the radar to the left instead of having the visual controls overlapping the radar
- [x] change  licnence to  GPL-2 add licencetext in the root folder
- [x] use server-provided colour legend for accurate radar spoke colours (was hardcoded green)
- [x] transparent background for no-return areas (legend index 0)
 

- [x] improve the app info screen
- [x] make section on the top of the screen and show the app logo there
  - [x] show the operating time and the transmit time. 
  - [x] Fix the app licecnce. 
  - [x] Operating time
- [x] add  Model name
- [x] add  Serial Number
- [x] add the spokes & spoke lenght  
- [x] add nice readme, explaining what the application is doing (showing the radar of mutliple brands) of the app and add 2 screenshots, one witht he connection screen (already there) and one with a radar working. the readme is more  of a mini usermanual, it does not need to explain the technical details in much details.
- [x] put the name of the device 

- [x] after all spokes are   fully rendered it seems they never disapear and provide a new refershed radar image
- [x] the app does not show the updated range that the server is providing. it remains static
- [x] The UI is not looking modern and nice, improve the icons used esp for the range changing. The setting icon is not nicely aligned and sized. 
- [x] the name is at strange location







## Backlog (Post-MVP)

- [ ] ARPA target tracking overlay
- [ ] AIS overlay
- [ ] Tablet landscape layout with floating bottom sheet

### phase II
Now that the core application is functional, here are the recommended improvements for the next iterations to make it marine-ready:
1. Thermal & Battery Management (Adaptive Rendering)
Issue: Rendering OpenGL at 2048 spokes per second constantly will drain mobile batteries rapidly and cause thermal throttling.
Solution: Implement a dynamic render loop. If the user hasn't touched the screen in 10 seconds, drop the visual OpenGL refresh rate to 30 FPS, even though the internal Kotlin WebSocket flow continues processing the full 2048 spokes silently. Pause OpenGL rendering entirely onPause() when the app goes to the background.
2. JNI Panic Handling
Issue: If the Rust mayara-server encounters a fatal error (e.g., a hardware network timeout) and panics, it will instantly crash the entire Android application without a stack trace.
Solution: Implement catch_unwind in the mayara-jni Rust bridge. If a panic occurs, safely catch it in Rust, translate it into an error string, and throw a native Kotlin Exception back to the UI to show a clean "Connection Lost" modal.
3. Wake-Locks & Screen Management
Feature: Add an Android FLAG_KEEP_SCREEN_ON rule whenever the radar state is in TRANSMIT. Boaters cannot afford to have their phone screen go to sleep/lock automatically while navigating through fog or at night.
4. AIS Target Overlay via SignalK
Feature: Since the app already connects to SignalK for Heading/SOG (HUD Overlay), the next logical step is to parse AIS (Automatic Identification System) targets from the SignalK stream. You can pass these coordinates into the OpenGL canvas to render standard marine AIS triangles directly on top of the radar echoes.
5. Foreground Service Persistence
Feature: Ensure that when the embedded Standalone server is running, Android triggers a Foreground Service with a sticky notification (e.g., "Radar Server Active"). Otherwise, modern Android OS memory management will kill the embedded Rust server aggressively if the user switches to a different app (like a weather app) for more than a few seconds.