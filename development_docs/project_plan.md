# Project Plan: Mayara Android Radar App

## Overview

A native Android application (Kotlin + Jetpack Compose) that visualises and controls marine
radars via the Rust-based mayara-server. The app supports a **Dual-Mode Architecture**:

- **Embedded Mode**: `libradar.so` compiled from a forked mayara-server runs in-process via JNI.
  The JNI layer starts an axum HTTP/WebSocket server on `127.0.0.1:6502`.
- **Network Mode**: Connects to a remote `mayara-server` or SignalK server discovered via mDNS.

Because the JNI layer simply starts a local server, **all Android client code (REST + WebSocket) is
identical for both modes**. This is the central architectural advantage.

---

## Repository Layout

```
mayara-app/
├── .gitmodules                        ← submodule: fork of MarineYachtRadar/mayara-server
├── AGENTS.md                          ← AI coding agent context & rules
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
│
├── development_docs/                  ← ALL planning/design docs live here
│   ├── application_specification.md
│   ├── project_plan.md                ← this file
│   ├── testing_plan.md
│   ├── todo.md
│   ├── folder_structure.md
│   └── agent_instructions.md
│
├── mayara-server/                     ← Git submodule → YOUR fork
│                                         JNI-related changes committed to fork
│
├── mayara-jni/                        ← Thin Rust crate: JNI bridge
│   ├── Cargo.toml
│   └── src/lib.rs
│
├── scripts/
│   ├── update_mayara.sh               ← git submodule update --remote → rebuild
│   ├── build_jni.sh                   ← cargo ndk → arm64-v8a libradar.so
│   └── copy_so.sh                     ← copies .so to app/src/main/jniLibs/
│
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── jniLibs/arm64-v8a/libradar.so
        │   └── kotlin/com/marineyachtradar/mayara/
        │       ├── MainActivity.kt
        │       ├── jni/RadarJni.kt
        │       ├── data/
        │       │   ├── api/RadarApiClient.kt
        │       │   ├── api/SpokeWebSocketClient.kt
        │       │   ├── model/RadarModels.kt
        │       │   └── nsd/MdnsScanner.kt
        │       ├── domain/
        │       │   ├── RadarRepository.kt
        │       │   └── ConnectionManager.kt
        │       └── ui/
        │           ├── radar/RadarScreen.kt
        │           ├── radar/gl/RadarGLRenderer.kt
        │           ├── radar/overlay/PowerToggle.kt
        │           ├── radar/overlay/RangeControls.kt
        │           ├── radar/overlay/HudOverlay.kt
        │           ├── radar/bottomsheet/RadarControlSheet.kt
        │           ├── connection/ConnectionPickerDialog.kt
        │           └── settings/SettingsActivity.kt
        ├── test/
        └── androidTest/
```

---

## Development Phases

### Phase 0 — Scaffolding & Docs ✅ (current)
**Goal**: Everything is in place before writing application logic.

| Task | Owner | Notes |
|------|-------|-------|
| Fork `MarineYachtRadar/mayara-server` to user GitHub | Human | Required before `git submodule add` |
| `git submodule add <fork-url> mayara-server` | Human | Run once in repo root |
| Create `development_docs/` planning files | Agent | This phase |
| Create root `AGENTS.md` | Agent | AI agent context file |
| Scaffold Android project (Gradle files, manifest) | Agent | User opens in Android Studio |
| Scaffold `mayara-jni/` Rust crate skeleton | Agent | Phase 1 fills in logic |
| Write `scripts/build_jni.sh`, `update_mayara.sh` | Agent | |
| GitHub Actions CI: `cargo test` + `./gradlew test` | Agent | |

### Phase 1 — mayara-server Fork: Android JNI Target
**Goal**: `scripts/build_jni.sh` exits 0 and produces `app/src/main/jniLibs/arm64-v8a/libradar.so`.

| Task | Owner | Notes |
|------|-------|-------|
| Add `android` feature flag to fork's `Cargo.toml` | Agent | Gate netlink/terminal_size behind non-android |
| Write `mayara-jni/src/lib.rs`: `startServer`, `stopServer`, `getLogs` JNI functions | Agent | Contains Tokio runtime management |
| Write `app/.../jni/RadarJni.kt` with `external fun` declarations | Agent | `System.loadLibrary("radar")` |
| Write `scripts/build_jni.sh` using `cargo-ndk` | Agent | |
| Document how to install prerequisites (`cargo-ndk`, NDK) | Agent | |

**Verification**: `adb shell` + network call to `127.0.0.1:6502/signalk/v2/api/vessels/self/radars` returns JSON.

### Phase 2 — Network Client & Data Layer
**Goal**: `StateFlow<RadarUiState>` populated from both live spoke data and REST capabilities.

| Task | Owner | Notes |
|------|-------|-------|
| Add `wire-gradle-plugin` protobuf codegen; source: `mayara-server/src/lib/protos/RadarMessage.proto` | Agent | Auto-syncs with submodule |
| Write `RadarApiClient.kt` (OkHttp3 REST) | Agent | `getRadars()`, `getCapabilities()`, `putControl()` |
| Write `SpokeWebSocketClient.kt` | Agent | Binary WS frames → decode `RadarMessage` proto → `Flow<SpokeData>` |
| Write `MdnsScanner.kt` (`NsdManager`) | Agent | Scans `_signalk-ws._tcp`, emits discovered servers |
| Write `ConnectionManager.kt` | Agent | Holds `ConnectionMode` (Embedded/Network), persist via DataStore |
| Write `RadarRepository.kt` | Agent | Merges REST + spoke stream into `StateFlow<RadarUiState>` |

**Verification**: `./gradlew test` passes all data-layer unit tests with OkHttp MockWebServer.

### Phase 3 — OpenGL ES Renderer
**Goal**: Radar sweep visible on screen, panning working, pinch-to-zoom disabled.

| Task | Owner | Notes |
|------|-------|-------|
| `RadarGLView.kt` wrapping `GLSurfaceView` | Agent | `AndroidView` inside Compose |
| `RadarGLRenderer.kt` polar-to-texture mapping | Agent | 512×512 texture updated per spoke |
| GLSL palette shader (green / yellow / multicolor / night-red) | Agent | LUT uniform |
| Pan gesture (1-finger drag → center offset uniform) | Agent | |
| Double-tap reset gesture | Agent | |
| Pinch gesture consumer that discards the event | Agent | Safety-critical: must be verified |

**Verification**: Compose UI test asserts pinch gesture does NOT change zoom level.

### Phase 4 — Compose UI Overlays
**Goal**: All primary controls and bottom sheet fully functional against mock `RadarUiState`.

| Task | Owner | Notes |
|------|-------|-------|
| `PowerToggle.kt` pill-shaped state machine | Agent | Drives `PUT .../controls/power` |
| `RangeControls.kt` +/- FABs stepping `capabilities.ranges[]` | Agent | Monospace font for range text |
| `HudOverlay.kt` heading/SOG/COG | Agent | Hidden when navigation data null |
| `RadarControlSheet.kt` bottom sheet | Agent | Gain/Sea/Rain sliders + Auto toggle; Palette; Orientation |
| `ConnectionPickerDialog.kt` | Agent | "Remember my choice" via DataStore |
| `RadarScreen.kt` composing all layers | Agent | |

**Verification**: All composables screenshotted via Compose Preview + Robolectric screenshot tests.

### Phase 5 — Settings Activity
**Goal**: Full settings flow accessible from Hamburger/Gear icon.

| Task | Owner | Notes |
|------|-------|-------|
| `SettingsActivity.kt` with Compose Navigation | Agent | |
| Connection Manager screen (status, Switch, manual IP/port) | Agent | |
| Server Logs screen (polls `RadarJni.getLogs()`) | Agent | |
| Units & Formats screen (NM/KM/SM, True/Magnetic) | Agent | DataStore backed |
| App Info screen (version, license, firmware) | Agent | |

### Phase 6 — Integration, Testing & Polish
**Goal**: Full integration tests pass on emulator; CI green.

| Task | Owner | Notes |
|------|-------|-------|
| Integration smoke test: JNI start → REST query → spoke WebSocket → GLRenderer | Agent | Uses `--emulator` mode |
| GitHub Actions full CI pipeline | Agent | |
| Night-vision Red/Black mode toggle | Agent | |
| Battery / performance profiling pass | Human | Android Profiler in Android Studio |

---

## Key Architectural Decisions

| Decision | Rationale |
|----------|-----------|
| JNI starts axum on `localhost:6502` | Android client code identical for both modes |
| Fork as Git submodule | `update_mayara.sh` pulls upstream changes; JNI patches tracked in fork |
| Wire protobuf codegen reading submodule `.proto` directly | Stays in sync with server automatically |
| Pinch-to-zoom disabled, not gated | Safety — marine scale must match PRF exactly |
| No hardcoded ranges/controls | UI built entirely from `capabilities` response |
| Tokio explicit feature subset for JNI | Avoids `io-uring` / `signal` on Android that cause panics |

---

## Prerequisites (Human Setup Steps)

1. **Fork the repo**: Go to `https://github.com/MarineYachtRadar/mayara-server` → Fork
2. **Add submodule**:
   ```bash
   git submodule add https://github.com/YOUR_USERNAME/mayara-server mayara-server
   git submodule update --init --recursive
   ```
3. **Install Rust + Android toolchain**:
   ```bash
   rustup target add aarch64-linux-android
   cargo install cargo-ndk
   ```
4. **Install Android NDK r26b** via Android Studio → SDK Manager → SDK Tools → NDK
5. Set `ANDROID_NDK_HOME` environment variable to NDK path
6. **Open Android Studio** and open the `mayara-app/` root directory
