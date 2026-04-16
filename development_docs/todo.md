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

## Phase 2 — Network Client & Data Layer

- [ ] Add `wire-gradle-plugin` to `app/build.gradle.kts`; configure `.proto` source from submodule path
- [ ] Verify protobuf codegen produces `RadarMessage` Kotlin class
- [ ] Write `RadarApiClient.kt`: `getRadars()`, `getCapabilities(id)`, `putControl(id, cid, value)`
- [ ] Write `SpokeWebSocketClient.kt`: WS connect, binary proto decode, `Flow<SpokeData>`
- [ ] Write `MdnsScanner.kt`: `NsdManager` scan for `_signalk-ws._tcp` and `_mayara._tcp`
- [ ] Write `ConnectionManager.kt`: `ConnectionMode` sealed class, persist via DataStore
- [ ] Write `RadarRepository.kt`: `StateFlow<RadarUiState>` aggregating REST + WS + JNI state
- [ ] Write `CapabilitiesMapper.kt`: maps capabilities JSON to `List<ControlDefinition>`
- [ ] Write JVM unit tests for all data-layer classes (Layer 3 in testing plan)
- [ ] `./gradlew test` passes all data-layer tests

---

## Phase 3 — OpenGL ES Renderer

- [ ] Write `RadarGLView.kt`: `AndroidView` wrapping `GLSurfaceView`
- [ ] Write `RadarGLRenderer.kt`: 512×512 `GL_LUMINANCE` texture allocated on startup
- [ ] Implement spoke-to-texture: `angle` → column, `data` bytes → row luminance
- [ ] Implement range scaling: `spoke.range` / `canvas_radius` determines pixel scale
- [ ] Write GLSL vertex and fragment shaders with palette LUT uniform
- [ ] Implement palette switching: Green / Yellow / Multicolor / Night-Red
- [ ] Implement pan gesture (1-finger drag → `centerOffset` uniforms)
- [ ] Implement double-tap reset (`centerOffset` → 0)
- [ ] Write pinch gesture consumer that discards zoom events (safety-critical)
- [ ] Write gesture unit tests (Layer 4c)
- [ ] Paparazzi screenshot for radar canvas in each palette mode

---

## Phase 4 — Compose UI Overlays

- [ ] Write `PowerToggle.kt`: OFF/WARMUP/STANDBY/TRANSMIT pill button with countdown
- [ ] Write `RangeControls.kt`: +/- FABs, monospace range text, stepping from capabilities array
- [ ] Write `HudOverlay.kt`: heading/SOG/COG, hidden when null
- [ ] Write `RadarControlSheet.kt`: bottom sheet with Gain/Sea/Rain/IR sliders + Auto toggles + Palette + Orientation
- [ ] Write `ConnectionPickerDialog.kt`: modal with Embedded/Network choice, "Remember" checkbox
- [ ] Write `RadarScreen.kt`: compose all layers in a `Box`
- [ ] Compose component tests for each overlay (Layer 4a)
- [ ] Paparazzi screenshots for day + night + minimal-capabilities variants
- [ ] Visual review of screenshots (agent-generated description + image diff)

---

## Phase 5 — Settings Activity

- [ ] Write `SettingsActivity.kt` with Compose Navigation host
- [ ] Write `ConnectionSettingsScreen.kt`: status, Switch button, manual IP/port
- [ ] Write `EmbeddedServerLogsScreen.kt`: polling `RadarJni.getLogs()` every 2 s
- [ ] Write `UnitsScreen.kt`: distance (NM/KM/SM) and bearing (True/Magnetic) with DataStore
- [ ] Write `AppInfoScreen.kt`: version, license, radar firmware (from capabilities)
- [ ] Settings navigation unit tests
- [ ] Hamburger/Gear icon in `RadarScreen` navigates to Settings correctly

---

## Phase 6 — Integration, CI, Polish

- [ ] Write integration test `IntegrationEmbeddedModeTest.kt` (Layer 5 in testing plan)
- [ ] Write integration test `IntegrationControlRoundTripTest.kt`
- [ ] Wire full CI pipeline in `.github/workflows/ci.yml`
- [ ] Enable Paparazzi screenshot diffing in CI
- [ ] Night-vision Red/Black mode toggle wired to DataStore preference
- [ ] App icon and branding
- [ ] ProGuard/R8 rules for Rust JNI symbols
- [ ] Performance benchmarks (Layer 6 in testing plan) — run locally
- [!] **HUMAN**: Manual testing checklist from `testing_plan.md`
- [!] **HUMAN**: Test on physical Navico/Furuno/Raymarine radar if available

---

## Backlog (Post-MVP)

- [ ] ARPA target tracking overlay
- [ ] AIS overlay
- [ ] Dual-range display mode
- [ ] Doppler colour coding
- [ ] Recording playback (pcap replay mode)
- [ ] TLS support for remote `mayara-server`
- [ ] Tablet landscape layout with floating bottom sheet
