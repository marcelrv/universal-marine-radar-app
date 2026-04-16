# Testing Plan: Mayara Android Radar App

## Philosophy

**Maximize automated testing; minimize manual testing.**
All test suites are runnable by an AI coding agent with zero hardware. Manual testing by a human
is reserved exclusively for hardware verification (physical radar or physical device).

---

## Test Layers

### Layer 1 — Rust Unit Tests (inside `mayara-server` fork)
*What*: Protocol parsing, control logic, ARPA tracker, spoke data encoding.
*Runner*: `cargo test` in `mayara-server/`
*Agent-runnable*: Yes — the emulator feature replaces real hardware.

| Suite | Focus | Command |
|-------|-------|---------|
| Brand protocol decoders | Navico/Furuno/Raymarine byte parsing | `cargo test --features emulator` |
| ARPA tracker | CPA/TCPA calculations | `cargo test -p mayara` |
| Control value round-trip | PUT control → GET control returns same value | `cargo test` |
| Spoke protobuf encoding | RadarMessage encode/decode round-trip | `cargo test` |
| Replay (pcap) | Feed captured traffic, assert spoke counts | `cargo test --features emulator` |

**Coverage target**: ≥ 80% line coverage on `src/lib/` (measured via `cargo tarpaulin`).

---

### Layer 2 — Rust JNI Unit Tests (inside `mayara-jni`)
*What*: JNI lifecycle, shutdown correctness, log buffer.
*Runner*: `cargo test` in `mayara-jni/`
*Agent-runnable*: Yes

| Test | Assertion |
|------|-----------|
| `test_start_stop_lifecycle` | `start(6502, true)` returns true; `stop()` does not panic; second `stop()` is idempotent |
| `test_double_start` | Second `start()` while already running returns false |
| `test_log_buffer_appends` | After start, `get_logs()` contains "started" message |
| `test_port_in_use` | `start()` on a busy port returns false and logs error message |
| `test_runtime_cleanup` | After `stop()`, spawning a new `start()` succeeds (runtime reuse-safety) |

---

### Layer 3 — JVM Unit Tests (`app/src/test/`)
*What*: Data layer, domain logic — no Android framework, no hardware.
*Runner*: `./gradlew test`
*Agent-runnable*: Yes

Libraries used: **JUnit 5**, **MockK**, **kotlinx-coroutines-test**, **OkHttp MockWebServer**, **Turbine** (Flow testing).

| Class Under Test | Test File | Key Scenarios |
|-----------------|-----------|---------------|
| `RadarApiClient` | `RadarApiClientTest.kt` | Parses radars JSON; parses capabilities JSON; PUT control returns success; HTTP 404 throws typed error; network timeout handled |
| `SpokeWebSocketClient` | `SpokeWebSocketClientTest.kt` | Binary frame decoded to correct `SpokeData`; connection closed emits `Flow` completion; reconnect on transient error |
| `MdnsScanner` | `MdnsScannerTest.kt` | Service discovered emits `DiscoveredServer`; service lost removes from list |
| `ConnectionManager` | `ConnectionManagerTest.kt` | Persists mode to DataStore; "Remember choice" suppresses dialog on next launch; switch resets mode |
| `RadarRepository` | `RadarRepositoryTest.kt` | `RadarUiState` emits initial Loading; emits capabilities after handshake; control PUT updates state; power state transitions |
| `CapabilitiesMapper` | `CapabilitiesMapperTest.kt` | Missing control → `ControlState.Unsupported`; ranges array parsed correctly |

**Coverage target**: ≥ 85% on `data/` and `domain/` packages.

---

### Layer 4 — Compose UI Tests (`app/src/androidTest/`)
*What*: UI correctness, composable behaviour under various `RadarUiState` values.
*Runner*: `./gradlew connectedAndroidTest` (on emulator or device) or Robolectric for most tests.
*Agent-runnable*: Yes — use Robolectric to run off-device.

Libraries used: **Compose Testing**, **Robolectric**, **Paparazzi** (screenshot tests).

#### 4a. Component Tests (Robolectric, no emulator needed)

| Composable | Scenarios |
|------------|-----------|
| `PowerToggle` | Shows OFF state; click transitions to WARMUP; shows countdown in WARMUP; TRANSMIT shows active style |
| `RangeControls` | `+` disabled at max range; `-` disabled at min range; range text uses monospace font |
| `HudOverlay` | Renders when navigation data present; completely absent from composition when data is null |
| `RadarControlSheet` | Gain slider disabled when Auto is selected; Rain Clutter row absent when not in capabilities; palette selector shows all 4 options |
| `ConnectionPickerDialog` | "Remember my choice" checkbox visible; selecting Network emits correct `ConnectionMode`; selecting Embedded emits correct mode |
| `SettingsActivity` | Navigation to each screen works; back navigation returns to parent |

#### 4b. Screenshot Tests (Paparazzi)

Each composable is snapshot-tested at:
- Day mode (default dark theme): `CONNECTED_TRANSMIT`, `CONNECTED_STANDBY`
- Night mode (Red/Black): `NIGHT_TRANSMIT`
- Capabilities: all controls present; minimal capabilities (no rain/sea/gain)

Screenshot deltas > 2% fail the build.

#### 4c. Gesture Tests (On-Device / Emulator required)

| Test | Assertion |
|------|-----------|
| `testPinchGestureIsConsumed` | Perform `pinchOut()` on radar canvas; `GLRenderer.zoomLevel` remains 1.0 (unchanged) |
| `testDoubleTapResetsOffset` | Pan to offset (100, 100); double-tap; `GLRenderer.centerOffsetX/Y` returns to 0 |
| `testSingleFingerPan` | Drag from center to edge; `GLRenderer.centerOffsetX` ≠ 0 |

---

### Layer 5 — Integration Tests
*What*: Full stack: JNI server start → REST capabilities fetch → spoke WebSocket → GL texture updated.
*Runner*: Custom Android instrumented test using `RadarJni` + OkHttp + in-process GL check.
*Agent-runnable*: Yes (with emulator mode — no physical radar needed)

| Test | Steps | Pass criteria |
|------|-------|---------------|
| `IntegrationEmbeddedModeTest` | 1. `RadarJni.startServer(6502, true)` <br>2. GET `/signalk/v2/api/vessels/self/radars` <br>3. GET `capabilities` <br>4. Subscribe to spoke WS <br>5. Receive ≥ 1 revolution of spokes | All steps succeed within 30s |
| `IntegrationControlRoundTripTest` | Start embedded; PUT gain value 50; GET gain; assert returned value ≈ 50 | GET returns 50 ±2 |
| `IntegrationPowerCycleTest` | Start embedded; PUT power=STANDBY; wait WARMUP; assert state=STANDBY | State transitions within 60s |
| `IntegrationNetworkModeTest` | Start standalone `mayara-server --emulator` process on host; connect Android app via network; receive spokes | At least 1 revolution received |

---

### Layer 6 — Performance / Regression Tests
*What*: Frame rate, battery, memory — catches regressions in the GL renderer.
*When*: Run weekly in CI or before each release.
*Agent-runnable*: Partially (macrobenchmark can be automated).

| Benchmark | Target |
|-----------|--------|
| GL renderer sustained FPS with emulator spoke stream | ≥ 60 FPS on Pixel 6 |
| Memory delta after 5 min active radar | < 20 MB growth |
| CPU usage while in TRANSMIT mode | < 15% average on arm64 |
| Cold start to radar display visible | < 2 seconds |

---

## CI Pipeline

```yaml
on: [push, pull_request]

jobs:
  rust-tests:
    runs-on: ubuntu-latest
    steps:
      - cargo test --manifest-path mayara-server/Cargo.toml --features emulator
      - cargo test --manifest-path mayara-jni/Cargo.toml

  android-unit-tests:
    runs-on: ubuntu-latest
    steps:
      - ./gradlew test                  # JVM unit tests
      - ./gradlew validateScreenshots   # Paparazzi screenshot diffing

  android-instrumented-tests:
    runs-on: ubuntu-latest
    steps:
      - Start AVD (arm64 API 26)
      - ./gradlew connectedAndroidTest  # Layer 4a + 4c

  integration-tests:
    runs-on: ubuntu-latest
    steps:
      - scripts/build_jni.sh            # build libradar.so
      - Start AVD
      - ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=IntegrationEmbeddedModeTest
```

---

## Manual Testing Checklist (Human, Hardware Required)

The following checklist is the ONLY part that requires a human with physical hardware.
It should be performed once per release candidate:

- [ ] Physical ARM64 Android device, radar emulator mode: radar sweep visible, smooth rotation
- [ ] Physical Navico HALO radar: discover via mDNS, connect, view sweep, change range
- [ ] Night mode: Red/Black palette active, all UI elements visible in dark
- [ ] Connection picker: modal appears on first launch; "Remember my choice" suppresses on second launch
- [ ] Settings → Server Logs: log output scrolls, shows real messages
- [ ] Gain slider Auto/Man toggle: slider becomes disabled in Auto mode
- [ ] Power cycle: OFF → TRANSMIT → STANDBY → OFF completes without crash

Total estimated manual test time per release: **~30 minutes**.
