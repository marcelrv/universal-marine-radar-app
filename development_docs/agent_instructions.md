# Agent Instructions: Mayara Android App

These instructions apply to all AI coding agents (GitHub Copilot, Claude, etc.) working on
this repository. Read this file in full before making any changes.

---

## Project Context

This is a native Android app (Kotlin + Jetpack Compose) that visualises and controls marine
radars. The backend is a forked Rust server (`mayara-server`, as a Git submodule) compiled to
`libradar.so` via JNI. See `application_specification.md` for full feature requirements and
`project_plan.md` for the phased implementation plan.

**Current phase**: Check `todo.md` for what is in progress.

---

## Non-Negotiable Rules

### Safety
1. **Pinch-to-zoom MUST be disabled**, not just gated. The gesture consumer must intercept and
   discard pinch events. Any implementation that allows zoom must be rejected. Write a test.
2. **Ranges are never hardcoded**. The `+`/`-` range buttons step through `capabilities.ranges[]`
   only. If capabilities haven't loaded, buttons are disabled.
3. **Missing controls are hidden/disabled**. If a control is absent from `capabilities.controls`,
   its UI element must not be visible or must show an "Unsupported" indicator. Never render a
   slider for a capability that is not in the capabilities response.

### Architecture
4. **Android client code must be identical for Embedded and Network modes.** The only difference
   is the server URL (`127.0.0.1:6502` vs discovered/manual IP). Do not add `if (embedded)` forks
   in `RadarApiClient` or `SpokeWebSocketClient`.
5. **`RadarRepository` is the single source of truth** for the UI. Composables observe
   `StateFlow<RadarUiState>` only — they do not call API clients directly.
6. **Never block the main thread in JNI.** `RadarJni.startServer()` must be called from
   `Dispatchers.IO`. The Kotlin wrapper should enforce this with `withContext(Dispatchers.IO)`.

### Code Quality
7. **JNI function names are exact**. The Rust function name format is:
   `Java_<package_underscores>_<ClassName>_<methodName>`. For example:
   `Java_com_marineyachtradar_mayara_jni_RadarJni_nativeStart`. A mismatch causes
   `UnsatisfiedLinkError` at runtime — no compiler error.
8. **Protobuf is generated, not hand-written**. `RadarMessage.kt` is produced by
   `wire-gradle-plugin` from `mayara-server/src/lib/protos/RadarMessage.proto`. Never edit the
   generated file. If the proto needs changing, modify it in the submodule fork.
9. **`DataStore` for all user preferences**. Never use `SharedPreferences`. Keys are defined as
   constants in `ConnectionManager.kt` or `UnitsScreen.kt`.

---

## Kotlin Coding Standards

- Use `StateFlow` + `collect`/`collectAsState` for all UI state. Avoid `LiveData`.
- Use `Dispatchers.IO` for all network and JNI calls. Use `Dispatchers.Main` for UI updates.
- Annotate all `@Composable` functions that take a `RadarUiState` or sub-state parameter with
  `@PreviewParameter` for Compose Preview support.
- Use `LaunchedEffect(key)` for side-effects triggered from composition.
- All `ViewModel`s expose state as `val state: StateFlow<UiState>` (immutable exposure).
- Prefer `sealed class`/`sealed interface` for state and event types.
- `RadarUiState` and all its nested states are `data class`es — use `copy()` for updates.

### Naming Conventions
| Element | Convention |
|---------|------------|
| Screens / Activities | `ConnectionPickerDialog.kt`, `SettingsActivity.kt` |
| Composables | PascalCase functions: `PowerToggle`, `RangeControls` |
| ViewModels | `RadarViewModel` (not `RadarVm`) |
| StateFlow keys | camelCase: `radarUiState`, `connectionMode` |
| DataStore keys | `preferencesKey("connection_mode")` — snake_case string |
| JNI methods | `native` prefix: `nativeStart`, `nativeStop`, `nativeGetLogs` |

---

## Rust / JNI Coding Standards

- The `mayara-jni` crate name is `radar` in `[lib]` so it produces `libradar.so`.
- Never `panic!()` in JNI code. Panics across the JNI boundary cause the JVM to crash. Use
  `Result`/`Option` and return error indicators to the Kotlin caller.
- Use `OnceLock<Mutex<T>>` for shared server state. Do not use `unsafe static mut`.
- Tokio runtime features: use the explicit subset `["rt-multi-thread", "net", "time", "sync",
  "macros"]` — NOT `"full"`. The `"full"` feature enables `signal` which is unstable on Android.
- The Android log target is `android_logger`. Initialise it with:
  ```rust
  android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Debug));
  ```
- When building for Android: `ANDROID_NDK_HOME` must be set. Use `cargo ndk` to cross-compile.
  See `scripts/build_jni.sh` for the exact command.

---

## OpenGL ES Standards

- All GL calls must be on the GL thread (from `onDrawFrame` or `onSurfaceCreated`).
- The radar texture is a `GL_LUMINANCE` format, 512×512. (`GL_LUMINANCE` gives single-channel
  greyscale; the palette is applied in the fragment shader, not in the texture data.)
- Spoke data maps to the texture as:
  - `angle` (0..spokes_per_revolution) → column index in texture
  - Byte value (0..255) → row index (0 = radar center, last row = max range)
  - Byte value intensity → luminance value written to texture
- Uniforms:
  - `u_center`: `vec2` screen-space radar center (handles pan offset)
  - `u_scale`: float pixels-per-meter at current range
  - `u_palette`: int 0–3 selects active LUT
- Never call `GLES20.glViewport` from a composable; only from `onSurfaceChanged`.

---

## mayara-server Fork Guidelines

When modifying the `mayara-server` submodule (your fork):
1. Make changes in a feature branch of the fork.
2. Commit to the fork, then update `.gitmodules` commit hash here if needed.
3. JNI-specific code goes in `src/lib/server/android.rs` behind `#[cfg(target_os = "android")]`.
4. Do not break existing `cargo test` — all 80+ existing tests must pass.
5. Platform-specific dependency gates:
   - Android uses `target_os = "android"` (distinct from `"linux"`).
   - `terminal_size` must be guarded: add `#[cfg(not(target_os = "android"))]` at usage sites if it
     doesn't compile for Android.
6. Run `scripts/update_mayara.sh` after pulling upstream changes to rebuild `libradar.so`.

---

## Testing Requirements

Every new feature **must** include tests at the appropriate layer. See `testing_plan.md` for
the full test strategy.

- **New data-layer class**: JVM unit test in `src/test/` using MockK + MockWebServer.
- **New composable**: Robolectric component test + Paparazzi screenshot.
- **New JNI function**: Rust unit test in `mayara-jni/src/lib.rs`.
- **New gesture behaviour**: On-device instrumented test asserting the GL state.

The following scenarios are **mandatory** test coverage:
- Pinch-to-zoom does not change zoom level (safety)
- Missing control in capabilities → UI element not rendered
- `nativeStop()` is idempotent (no crash on double-stop)
- `RadarUiState.Loading` renders correctly (network before capabilities arrive)

---

## File Ownership

| Path | Who can edit |
|------|-------------|
| `development_docs/application_specification.md` | Human only |
| `development_docs/todo.md` | Agent (keep up to date) |
| `app/src/main/jniLibs/` | Build script only (`build_jni.sh`) |
| `app/build/generated/` | Gradle codegen only — never hand-edit |
| `mayara-server/` | Changes must be committed to the fork first |

---

## How to Update mayara-server

```bash
# Pull upstream changes into the fork and rebuild the .so:
bash scripts/update_mayara.sh

# This script does:
# 1. cd mayara-server && git fetch upstream && git merge upstream/main
# 2. cd .. && bash scripts/build_jni.sh
# 3. bash scripts/copy_so.sh
```

If upstream introduces breaking API changes that affect `mayara-jni/src/lib.rs`,
update the JNI bridge accordingly and run `cargo test --manifest-path mayara-jni/Cargo.toml`.
