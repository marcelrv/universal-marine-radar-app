# Folder Structure: Mayara Android App

```
mayara-app/
│
├── .gitmodules                         ← submodule: YOUR fork of MarineYachtRadar/mayara-server
├── .github/
│   └── workflows/
│       └── ci.yml                      ← CI: rust tests + android unit + screenshot tests
│
├── AGENTS.md                           ← AI coding agent context (must read before any edit)
├── settings.gradle.kts                 ← Gradle project name + plugin management
├── build.gradle.kts                    ← Root build: plugin declarations
├── gradle/
│   └── libs.versions.toml             ← Version catalog (single source of truth for deps)
│
├── development_docs/                   ← ALL planning and design documentation
│   ├── application_specification.md   ← Source of truth for features (DO NOT change without discussion)
│   ├── project_plan.md                ← Phases, tasks, architectural decisions
│   ├── testing_plan.md                ← Test layers, CI, manual checklist
│   ├── todo.md                        ← Living todo list
│   ├── folder_structure.md            ← This file
│   └── agent_instructions.md          ← Detailed coding rules for AI agents
│
├── mayara-server/                      ← GIT SUBMODULE → fork of MarineYachtRadar/mayara-server
│   │                                     Run `scripts/update_mayara.sh` to pull upstream
│   ├── Cargo.toml                      ← [lib] section; crate-type = ["rlib"] (fork adds cdylib)
│   ├── src/
│   │   ├── lib/                        ← Library code (public API used by mayara-jni)
│   │   │   ├── mod.rs                  ← pub start_session(), pub Cli, pub Brand
│   │   │   ├── server/                 ← (FORK ADDS THIS) Web server moved from bin/
│   │   │   ├── radar/                  ← Brand-agnostic radar abstractions
│   │   │   ├── brand/                  ← Per-brand protocol implementations
│   │   │   └── protos/                 ← RadarMessage.proto (used by wire codegen in Android)
│   │   └── bin/mayara-server/          ← Binary entry point (thin wrapper in fork)
│   └── ...
│
├── mayara-jni/                         ← Thin Rust crate: JNI bridge
│   ├── Cargo.toml                      ← crate-type=["cdylib"], lib name="radar" → libradar.so
│   └── src/
│       └── lib.rs                      ← 3 JNI fns: nativeStart, nativeStop, nativeGetLogs
│
├── scripts/
│   ├── build_jni.sh                    ← cargo ndk build → arm64-v8a, output to app/jniLibs/
│   ├── update_mayara.sh                ← git submodule update --remote && scripts/build_jni.sh
│   └── copy_so.sh                      ← copies target/aarch64-linux-android/release/libradar.so
│
└── app/
    ├── build.gradle.kts                ← Android module, AGP config, wire codegen, Kotlin
    ├── proguard-rules.pro              ← Keep JNI symbols, mayara model classes
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── jniLibs/
        │   │   └── arm64-v8a/
        │   │       └── libradar.so     ← Output of build_jni.sh (gitignored, rebuilt on demand)
        │   │
        │   └── kotlin/com/marineyachtradar/mayara/
        │       │
        │       ├── MainActivity.kt     ← Single activity; hosts Compose nav graph
        │       │
        │       ├── jni/
        │       │   └── RadarJni.kt     ← external fun nativeStart/Stop/GetLogs; loadLibrary
        │       │
        │       ├── data/
        │       │   ├── api/
        │       │   │   ├── RadarApiClient.kt         ← OkHttp3 REST: getRadars, getCapabilities, putControl
        │       │   │   └── SpokeWebSocketClient.kt   ← OkHttp3 WebSocket: binary proto → Flow<SpokeData>
        │       │   ├── model/
        │       │   │   ├── RadarModels.kt             ← Data classes: RadarInfo, Capabilities, ControlDef, SpokeData
        │       │   │   └── UiState.kt                 ← RadarUiState, ControlState, ConnectionMode sealed classes
        │       │   └── nsd/
        │       │       └── MdnsScanner.kt             ← NsdManager: emits List<DiscoveredServer>
        │       │
        │       ├── domain/
        │       │   ├── RadarRepository.kt             ← StateFlow<RadarUiState>; aggregates REST+WS+JNI
        │       │   ├── ConnectionManager.kt           ← Holds ConnectionMode, "remember" DataStore pref
        │       │   └── CapabilitiesMapper.kt          ← Maps capabilities JSON to List<ControlDefinition>
        │       │
        │       └── ui/
        │           ├── radar/
        │           │   ├── RadarScreen.kt             ← Root composable: Box(GLView + overlays)
        │           │   ├── gl/
        │           │   │   └── RadarGLRenderer.kt     ← GLSurfaceView.Renderer; polar-to-texture mapping
        │           │   ├── overlay/
        │           │   │   ├── PowerToggle.kt         ← Pill button: OFF→WARMUP→STANDBY→TRANSMIT
        │           │   │   ├── RangeControls.kt       ← +/- FABs + range text (monospace)
        │           │   │   └── HudOverlay.kt          ← Heading/SOG/COG (hidden when null)
        │           │   └── bottomsheet/
        │           │       └── RadarControlSheet.kt   ← Bottom sheet: Gain/Sea/Rain/IR/Palette/Orientation
        │           ├── connection/
        │           │   └── ConnectionPickerDialog.kt  ← Modal: Embedded vs Network, "Remember" checkbox
        │           └── settings/
        │               ├── SettingsActivity.kt        ← Full-screen settings with Compose nav
        │               ├── ConnectionSettingsScreen.kt
        │               ├── EmbeddedServerLogsScreen.kt
        │               ├── UnitsScreen.kt
        │               └── AppInfoScreen.kt
        │
        ├── test/                                      ← JVM unit tests (no Android runtime)
        │   └── kotlin/com/marineyachtradar/mayara/
        │       ├── data/api/RadarApiClientTest.kt
        │       ├── data/api/SpokeWebSocketClientTest.kt
        │       ├── data/nsd/MdnsScannerTest.kt
        │       ├── domain/RadarRepositoryTest.kt
        │       ├── domain/ConnectionManagerTest.kt
        │       └── domain/CapabilitiesMapperTest.kt
        │
        └── androidTest/                               ← Instrumented tests (Compose + integration)
            └── kotlin/com/marineyachtradar/mayara/
                ├── ui/radar/overlay/PowerToggleTest.kt
                ├── ui/radar/overlay/RangeControlsTest.kt
                ├── ui/radar/bottomsheet/RadarControlSheetTest.kt
                ├── ui/connection/ConnectionPickerDialogTest.kt
                ├── ui/radar/gl/RadarGLRendererGestureTest.kt
                └── integration/IntegrationEmbeddedModeTest.kt
```

---

## Key File Relationships

```
scripts/build_jni.sh
    │
    ├── reads: mayara-server/  (submodule)
    ├── reads: mayara-jni/
    └── writes: app/src/main/jniLibs/arm64-v8a/libradar.so

app/build.gradle.kts (wire codegen)
    │
    └── reads: mayara-server/src/lib/protos/RadarMessage.proto
        └── generates: app/build/generated/source/wire/…/RadarMessage.kt

RadarJni.kt
    │
    ├── calls: libradar.so!Java_…_nativeStart
    ├── calls: libradar.so!Java_…_nativeStop
    └── calls: libradar.so!Java_…_nativeGetLogs

RadarRepository.kt
    │
    ├── calls: RadarJni.startServer() → embedded mode
    ├── calls: RadarApiClient.getCapabilities()
    └── collects: SpokeWebSocketClient.spokeFlow()
        └── emits: StateFlow<RadarUiState>
            └── consumed by: RadarScreen composables + RadarGLRenderer
```

---

## Files Never to Edit Without Understanding the Dependency

| File | Reason |
|------|--------|
| `mayara-server/src/lib/protos/RadarMessage.proto` | Changing this breaks Android proto codegen |
| `mayara-jni/src/lib.rs` — JNI function names | Name mangling must match `RadarJni.kt` exactly |
| `gradle/libs.versions.toml` | Single source of versions; bump carefully |
| `development_docs/application_specification.md` | Source of truth; changes require discussion |
