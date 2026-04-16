# Mayara Android App — AI Agent Context

This repository contains a native Android radar display application backed by an embedded Rust
server compiled as `libradar.so`. Read `development_docs/agent_instructions.md` before editing
any code.

## Quick Facts

| Item | Value |
|------|-------|
| Language | Kotlin (app) + Rust (JNI bridge) |
| UI Framework | Jetpack Compose on top of `GLSurfaceView` |
| Rendering | OpenGL ES (custom `RadarGLRenderer`) |
| Networking | OkHttp3 REST + WebSocket |
| Backend | `mayara-server` (Git submodule at `mayara-server/`) |
| JNI crate | `mayara-jni/` → produces `libradar.so` |
| Target ABI | arm64-v8a only |
| Min SDK | API 26 (Android 8.0) |

## Critical Rules (Short Version)

1. **Pinch-to-zoom must be disabled — not just gated.** Write a test.
2. **No hardcoded ranges or controls.** Everything comes from `capabilities` API response.
3. **Android client code is identical for Embedded and Network modes.** The JNI layer starts
   axum on `127.0.0.1:6502`; the client always speaks HTTP/WebSocket.
4. **Never panic in JNI.** Use `Result`/error returns only.
5. **`RadarRepository` is the single source of truth.** Composables observe state; they never
   call network or JNI directly.

## API Endpoints (from mayara-server)

Base: `http://127.0.0.1:6502` (embedded) or discovered/manual IP (network)

```
GET  /signalk/v2/api/vessels/self/radars                      → list of radars
GET  /signalk/v2/api/vessels/self/radars/{id}/capabilities    → ranges[], controls{}
GET  /signalk/v2/api/vessels/self/radars/{id}/controls/{cid}  → read control
PUT  /signalk/v2/api/vessels/self/radars/{id}/controls/{cid}  → write control
WS   /signalk/v2/api/vessels/self/radars/{id}/spokes          → binary protobuf RadarMessage
WS   /signalk/v1/stream                                       → JSON delta (control updates)
```

## Build the .so

```bash
# One-time setup:
rustup target add aarch64-linux-android
cargo install cargo-ndk
# (Android NDK r26b must be in ANDROID_NDK_HOME)

# Build:
bash scripts/build_jni.sh
```

## Update mayara-server from upstream

```bash
bash scripts/update_mayara.sh
```

## Run Tests

```bash
cargo test --manifest-path mayara-server/Cargo.toml --features emulator
cargo test --manifest-path mayara-jni/Cargo.toml
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # Compose + integration tests (needs emulator/device)
```

## Current Phase

See `development_docs/todo.md` for the live task list.
