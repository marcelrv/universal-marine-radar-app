# Universal Marine Radar Display for Android — Developer Guide

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1) or later
- JDK 17
- Android SDK 34 (compile), API 26+ (min)
- Rust toolchain (for building the embedded server `.so`)

### Build the .so (embedded mode)

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
# Set ANDROID_NDK_HOME to NDK r26b
bash scripts/build_jni.sh
```

### Build the APK

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Run tests

```bash
./gradlew test                    # JVM unit tests
./gradlew connectedAndroidTest    # Compose + integration tests (emulator/device)
cargo test --manifest-path mayara-jni/Cargo.toml
cargo test --manifest-path mayara-server/Cargo.toml --features emulator
```

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                 Jetpack Compose UI                    │
│  RadarScreen ─ HudOverlay ─ RangeControls ─ Sheet    │
├──────────────────────────────────────────────────────┤
│                RadarViewModel                        │
├──────────────────────────────────────────────────────┤
│  RadarRepository (single source of truth)            │
│  ├─ ApiClient (REST)                                 │
│  ├─ SpokeClient (WS binary protobuf)                │
│  └─ SignalKStreamClient (WS JSON delta)              │
├──────────────────────────────────────────────────────┤
│  RadarGLRenderer (OpenGL ES 2.0)                     │
├──────────────────────────────────────────────────────┤
│  mayara-jni → libradar.so (Rust, axum on 127.0.0.1) │
└──────────────────────────────────────────────────────┘
```

### Key design decisions

- **Single code path** — The Android client is identical in both embedded and
  network modes. The JNI layer starts axum on `127.0.0.1:6502`; the client
  always speaks HTTP/WebSocket to that address.
- **`RadarRepository` is the single source of truth.** Composables observe
  state via `StateFlow`; they never call network or JNI directly.
- **Capabilities-driven UI** — All ranges and controls come from the
  `capabilities` API response. Nothing is hardcoded.
- **Pinch-to-zoom is intentionally disabled** — The radar display uses
  explicit +/− range buttons only.

### JNI bridge (`mayara-jni/`)

The `mayara-jni` crate compiles to `libradar.so` and exposes three JNI
functions called from `RadarJni.kt`:

| Kotlin method     | Description                                     |
|-------------------|-------------------------------------------------|
| `nativeStart()`   | Create Tokio runtime, start axum server         |
| `nativeStop()`    | Graceful shutdown, drop runtime                 |
| `nativeGetLogs()` | Return buffered log lines as a Java `String`    |

### API endpoints

Base URL: `http://127.0.0.1:6502` (embedded) or discovered / manual IP (network).

```
GET  /signalk/v2/api/vessels/self/radars                      → list of radars
GET  /signalk/v2/api/vessels/self/radars/{id}/capabilities    → ranges[], controls{}
GET  /signalk/v2/api/vessels/self/radars/{id}/controls/{cid}  → read control
PUT  /signalk/v2/api/vessels/self/radars/{id}/controls/{cid}  → write control
WS   /signalk/v2/api/vessels/self/radars/{id}/spokes          → binary protobuf RadarMessage
WS   /signalk/v1/stream                                       → JSON delta (control updates)
```

### Folder structure

```
app/src/main/java/…/
  jni/          RadarJni.kt — System.loadLibrary + native method declarations
  network/      ApiClient, SpokeClient, SignalKStreamClient
  repository/   RadarRepository
  ui/           Jetpack Compose screens and components
  viewmodel/    RadarViewModel

mayara-jni/     Rust crate → libradar.so (JNI bridge)
mayara-server/  Git submodule — upstream mayara-server library
scripts/        build_jni.sh, copy_so.sh, update_mayara.sh
```

## CI / GitHub Actions

| Workflow         | Trigger           | What it does                                      |
|------------------|-------------------|---------------------------------------------------|
| `ci.yml`         | push / PR         | Rust + Android unit tests, builds debug APK       |
| `build-jni.yml`  | push / manual     | Builds `libradar.so`, commits artifact to `main`  |
