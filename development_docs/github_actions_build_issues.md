# GitHub Actions Build Issues Analysis

**Date:** April 17, 2026  
**Analysis Scope:** 
- `.github/workflows/ci.yml` — main CI workflow
- `.github/workflows/build-jni.yml` — JNI build workflow  
- `scripts/build_jni.sh` — JNI build script

---

## Executive Summary

The GitHub Actions CI/CD pipeline has **12 identified issues** across 3 severity levels that will **prevent successful APK builds and app deployment**. The most critical issue is the **missing APK build step** — the pipeline never verifies that the final deliverable can be built. Secondary issues include **invalid placeholder native library** and **architecture mismatch** between build and test environments.

---

## Critical Issues (Blocks Release)

### 1. ❌ **MISSING APK BUILD VERIFICATION** 
**Severity:** CRITICAL  
**File:** `.github/workflows/ci.yml`  
**Impact:** Build failures only caught at deployment time; no proof the app can be packaged

**Problem:**
- The CI workflow runs unit tests and instrumented tests but **never builds the APK**
- No `./gradlew build`, `./gradlew assembleDebug`, or `./gradlew assembleRelease` step
- Gradle compilation errors, missing dependencies, or packaging issues won't be caught
- Release would fail when attempting to deploy

**Current State:**
```yaml
# Android — JVM unit tests
android-unit-tests:
  steps:
    - uses: actions/checkout@v4
    - name: Set up JDK 17
    - name: Set up Android SDK
    - name: Create placeholder libradar.so
    - name: Run JVM unit tests        # ✓ runs
    # ✗ NO APK BUILD STEP HERE
```

**Required Fix:**
Add an APK build job after tests pass:
```yaml
android-build-apk:
  name: Android — Build APK
  needs: [android-unit-tests, android-instrumented-tests]
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - name: Set up JDK 17
    - name: Set up Android SDK
    - name: Build APK (debug)
      run: ./gradlew assembleDebug --stacktrace
    - name: Build APK (release)
      run: ./gradlew assembleRelease --stacktrace
    - name: Verify APK size
      run: |
        if [ ! -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
          echo "ERROR: APK not found!"
          exit 1
        fi
        ls -lh app/build/outputs/apk/*/app-*.apk
```

---

### 2. ❌ **INVALID PLACEHOLDER LIBRADAR.SO FOR INSTRUMENTED TESTS**
**Severity:** CRITICAL  
**Files:** `.github/workflows/ci.yml` (line ~140)  
**Impact:** Instrumented tests crash when app tries to `System.loadLibrary("radar")`

**Problem:**
- The `android-instrumented-tests` job creates a placeholder .so file using `touch`:
```bash
mkdir -p app/src/main/jniLibs/arm64-v8a
touch app/src/main/jniLibs/arm64-v8a/libradar.so  # Creates EMPTY file
```
- An empty file is **not a valid ARM64 ELF binary**
- When the Dalvik runtime on the emulator tries to load it, it fails with:
```
java.lang.UnsatisfiedLinkError: Failed to load native library. 
Error: dlopen failed: "..." is not an ELF file
```
- This blocks the entire `connectedAndroidTest` step

**Why This Matters:**
- The comment in `ci.yml` says "JVM tests do not load native lib" — that's **correct for JVM tests** using Robolectric
- But **instrumented tests run on an actual emulator** where the Dalvik runtime **IS** the real Android runtime
- It tries to load the actual native library, not a mock

**Required Fix:**
One of two options:

**Option A:** Skip instrumented tests until the JNI library can be built
```yaml
# Temporarily disable until build-jni.yml is integrated
android-instrumented-tests:
  if: false  # Disabled — requires full JNI build
```

**Option B:** Build actual arm64-v8a .so on Linux (requires cargo-ndk setup)
```yaml
android-instrumented-tests:
  needs: build-jni  # Depend on JNI build job
  steps:
    - uses: actions/checkout@v4
    - name: Copy actual libradar.so from JNI build
      run: |
        # Copy from build-jni.yml artifact or rebuild it
        # This requires full Rust toolchain + NDK setup
```

**Option C:** Use a minimal valid ARM64 stub binary (not recommended; adds tech debt)

---

### 3. ❌ **ARCHITECTURE MISMATCH: ARM64 BINARY ON X86_64 EMULATOR**
**Severity:** CRITICAL  
**File:** `.github/workflows/ci.yml` (line ~155)  
**Impact:** `libradar.so` (arm64-v8a) cannot load on x86_64 AVD

**Problem:**
- `build-jni.yml` builds **arm64-v8a** (ARM 64-bit)
- `android-instrumented-tests` runs on **x86_64 AVD**:
```yaml
uses: reactivecircus/android-emulator-runner@v2
with:
  api-level: 26
  arch: x86_64  # ← Mismatched!
  target: default
```
- When the app tries to load `app/src/main/jniLibs/arm64-v8a/libradar.so`, the emulator's Dalvik runtime **cannot execute ARM code on x86_64**
- Results in `UnsatisfiedLinkError`

**Why This Happens:**
- The JNI library is built for target devices (ARM64 mobile phones)
- The CI emulator is simulated on CPU (x86_64 for speed)
- These are incompatible at runtime

**Required Fix:**

**Option A (Recommended):** Build x86_64 variant for CI tests only
```yaml
build-android-jni-x86_64:  # New job
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - name: Install cargo-ndk
    - name: Build for x86_64
      run: |
        cargo ndk --target x86_64-linux-android --platform 26 -- build --release
        mkdir -p app/src/main/jniLibs/x86_64
        cp mayara-jni/target/x86_64-linux-android/release/libradar.so \
           app/src/main/jniLibs/x86_64/libradar.so
```

Then update the emulator:
```yaml
uses: reactivecircus/android-emulator-runner@v2
with:
  api-level: 26
  arch: x86_64  # Now matches the JNI binary
```

**Option B:** Skip instrumented tests on emulator, rely on unit tests only
```yaml
android-instrumented-tests:
  if: false  # Disabled — architecture mismatch
```

**Option C:** Use an ARM64 emulator (slower, but architecturally correct)
```yaml
arch: arm64  # Slower, but can run arm64-v8a .so
```

---

## High-Severity Issues (Prevents JNI Build)

### 4. ⚠️ **NO JNI BUILD STEP IN CI WORKFLOW**
**Severity:** HIGH  
**File:** `.github/workflows/ci.yml`  
**Impact:** Instrumented tests cannot run without actual libradar.so; manual builds required

**Problem:**
- The `ci.yml` workflow doesn't call `build-jni.sh`
- It only creates a placeholder .so
- The actual `build-jni.yml` workflow is **separate** and only runs when `mayara-jni/**` or `mayara-server/**` files change
- If the main code changes but no Rust changes, the JNI build is skipped
- Tests fail because they have an invalid placeholder

**Impact Chain:**
1. Developer commits Kotlin code change to `app/src/main/kotlin/`
2. `ci.yml` runs (unit + instrumented tests)
3. No JNI build is triggered (not in path filters)
4. Tests use invalid placeholder .so
5. Instrumented tests fail with `UnsatisfiedLinkError`

**Required Fix:**

Make the JNI build a prerequisite job in `ci.yml`:
```yaml
jobs:
  build-jni:
    name: Build JNI Library
    runs-on: ubuntu-latest
    # (Copy the entire build-jni.yml job here, or call it as dependency)
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive
      - name: Install Rust
      - name: Install cargo-ndk
      - name: Set up Android NDK
      # ... full build_jni.sh execution
      
  android-unit-tests:
    needs: build-jni  # ← Add dependency
    
  android-instrumented-tests:
    needs: build-jni  # ← Add dependency
```

Or use the **artifact upload** from `build-jni.yml`:
```yaml
android-instrumented-tests:
  needs: build-jni
  steps:
    - uses: actions/download-artifact@v4
      with:
        name: libradar-so-arm64
        path: app/src/main/jniLibs/arm64-v8a/
```

---

### 5. ⚠️ **CARGO-NDK NOT AVAILABLE IN CI ENVIRONMENT**
**Severity:** HIGH  
**File:** `.github/workflows/build-jni.yml` (line ~33)  
**Impact:** JNI build fails if cargo-ndk install fails silently

**Problem:**
- `build-jni.yml` installs cargo-ndk with `cargo install cargo-ndk --locked`
- No verification that it was actually installed or is in PATH
- If installation fails (network error, cache issue), subsequent `bash scripts/build_jni.sh` will fail with:
```
ERROR: cargo-ndk not found. Run: cargo install cargo-ndk
```

**Risk:**
- The workflow might appear to succeed but the .so is never built
- Artifact upload would fail silently
- The placeholder from `ci.yml` would be used instead

**Required Fix:**

Add verification step after installation:
```yaml
- name: Install cargo-ndk
  run: cargo install cargo-ndk --locked

- name: Verify cargo-ndk installation
  run: |
    if ! which cargo-ndk &> /dev/null; then
      echo "ERROR: cargo-ndk not in PATH after installation"
      cargo --version
      echo "Installed binaries:"
      ls -la ~/.cargo/bin/ | grep ndk || echo "  (not found)"
      exit 1
    fi
    cargo ndk --version
```

---

### 6. ⚠️ **NDK DOWNLOAD NETWORK FAILURE HAS NO RETRY LOGIC**
**Severity:** HIGH  
**File:** `.github/workflows/build-jni.yml` (lines ~40-55)  
**Impact:** Single network hiccup fails the entire build; ~800 MB download not cached

**Problem:**
```yaml
- name: Set up Android NDK
  run: |
    NDK_URL="https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux-x86_64.zip"
    wget -q "$NDK_URL" -O "$NDK_ZIP" || curl -L -o "$NDK_ZIP" "$NDK_URL"
    unzip -q "$NDK_ZIP"
```

**Issues:**
1. No caching — NDK (~800 MB) downloaded on every run
2. No retry logic — single timeout/failure fails the build
3. Fallback from `wget` to `curl` only works if wget fails, not if curl fails
4. No checksum verification

**Estimate Impact:**
- Wasted bandwidth: 800 MB × 10 runs/week = 8 GB/week
- Time: ~5-10 minutes per download on slow networks
- Fragility: 1-2% download failure rate × 10 runs/week = ~1 failure per week

**Required Fix:**

```yaml
- name: Set up Android NDK (cached)
  run: |
    NDK_VERSION="r26.1.10909125"
    NDK_DIR="$RUNNER_TEMP/android-ndk"
    NDK_PATH="${NDK_DIR}/android-ndk-${NDK_VERSION}"
    
    # Check if already cached
    if [ -d "$NDK_PATH" ]; then
      echo "Using cached NDK"
    else
      echo "Downloading NDK..."
      NDK_URL="https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux-x86_64.zip"
      NDK_ZIP="${NDK_DIR}/android-ndk-${NDK_VERSION}.zip"
      mkdir -p "$NDK_DIR"
      
      # Download with retries
      for attempt in 1 2 3; do
        echo "Attempt $attempt..."
        if wget -q --timeout=30 "$NDK_URL" -O "$NDK_ZIP" 2>/dev/null; then
          break
        elif curl -m 30 -L -o "$NDK_ZIP" "$NDK_URL" 2>/dev/null; then
          break
        elif [ $attempt -lt 3 ]; then
          sleep $((attempt * 10))
        else
          echo "ERROR: NDK download failed after 3 attempts"
          exit 1
        fi
      done
      
      echo "Extracting NDK..."
      unzip -q "$NDK_ZIP" -d "$NDK_DIR"
    fi
    
    echo "ANDROID_NDK_HOME=${NDK_PATH}" >> $GITHUB_ENV

- name: Cache NDK
  uses: actions/cache@v4
  with:
    path: ${{ runner.temp }}/android-ndk
    key: ndk-r26-linux
```

---

## Medium-Severity Issues (Degrades Reliability)

### 7. ⚠️ **SUBMODULE INITIALIZATION NOT VERIFIED**
**Severity:** MEDIUM  
**Files:** `.github/workflows/build-jni.yml` and `scripts/build_jni.sh`  
**Impact:** Cryptic error messages if submodule fails to initialize

**Problem:**
- Both workflows use `submodules: recursive` during checkout
- But there's no verification the submodule actually initialized
- If it fails, `mayara-server/` is empty or partial
- `build_jni.sh` has a check:
```bash
if [[ ! -d "${REPO_ROOT}/mayara-server" ]] || [[ -z "$(ls -A "${REPO_ROOT}/mayara-server")" ]]; then
    echo "ERROR: mayara-server submodule not initialised."
    exit 1
fi
```
- But this error only appears **after** the Rust build has already started

**Required Fix:**

Add explicit verification in `build-jni.yml`:
```yaml
- name: Verify submodule initialization
  run: |
    echo "==> Checking mayara-server submodule..."
    if [ ! -d "mayara-server/.git" ]; then
      echo "ERROR: mayara-server/.git not found"
      echo "Submodule may not have initialized correctly"
      echo "Contents of mayara-server/:"
      ls -la mayara-server/
      exit 1
    fi
    
    if [ ! -f "mayara-server/Cargo.toml" ]; then
      echo "ERROR: mayara-server/Cargo.toml not found"
      exit 1
    fi
    
    echo "✓ Submodule verified"
```

---

### 8. ⚠️ **GIT PUSH MAY FAIL WITH INSUFFICIENT PERMISSIONS**
**Severity:** MEDIUM  
**File:** `.github/workflows/build-jni.yml` (lines ~150-160)  
**Impact:** Build succeeds but artifact isn't committed; manual push required

**Problem:**
```yaml
- name: Commit and push libradar.so
  if: github.event_name == 'push' && github.ref == 'refs/heads/main'
  run: |
    git config user.name "GitHub Actions"
    git config user.email "actions@github.com"
    git add app/src/main/jniLibs/arm64-v8a/libradar.so
    git commit -m "build: libradar.so from GitHub Actions" || echo "No changes to commit"
    git push
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

**Issues:**
1. `GITHUB_TOKEN` by default has limited permissions; may not have `repo` scope
2. No error handling if push fails — the `|| echo` silently ignores commit failures
3. If the .so file already exists and is unchanged, `git commit` fails silently
4. Race condition: if two builds run simultaneously, second push may fail
5. The artifact is lost if the push fails

**Required Fix:**

```yaml
- name: Commit and push libradar.so
  if: github.event_name == 'push' && github.ref == 'refs/heads/main'
  run: |
    git config user.name "GitHub Actions Bot"
    git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
    
    # Check if file changed
    if git diff --quiet app/src/main/jniLibs/arm64-v8a/libradar.so 2>/dev/null; then
      echo "ℹ️  libradar.so unchanged, skipping commit"
      exit 0
    fi
    
    git add app/src/main/jniLibs/arm64-v8a/libradar.so
    
    if ! git commit -m "build: rebuild libradar.so [skip ci]"; then
      echo "ERROR: git commit failed"
      exit 1
    fi
    
    # Retry push up to 3 times (for race conditions)
    for attempt in 1 2 3; do
      echo "Push attempt $attempt..."
      if git push; then
        echo "✓ Push successful"
        exit 0
      fi
      if [ $attempt -lt 3 ]; then
        echo "⏳ Push failed, retrying in 10s..."
        git pull --rebase
        sleep 10
      fi
    done
    
    echo "ERROR: Push failed after 3 attempts"
    exit 1
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

**Better Alternative:** Don't commit the .so to git; instead, use GitHub Releases:
```yaml
- name: Create GitHub Release
  uses: softprops/action-gh-release@v1
  with:
    files: app/src/main/jniLibs/arm64-v8a/libradar.so
    tag_name: jni-build-${{ github.run_id }}
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

---

### 9. ⚠️ **UNUSED/COMMENTED-OUT JNI BUILD TRIGGER IN APP BUILD**
**Severity:** MEDIUM  
**File:** `app/build.gradle.kts` (lines ~68-73)  
**Impact:** Confuses developers; suggests full automation that doesn't exist

**Problem:**
```kotlin
// Trigger the JNI build before the Android build (optional: allows fully automated builds).
// Comment this out and run scripts/build_jni.sh manually if cargo-ndk is not on PATH.
//
// val buildJni = tasks.register<Exec>("buildJni") {
//     commandLine("bash", "../scripts/build_jni.sh")
//     workingDir = rootProject.projectDir
// }
// tasks.named("preBuild") { dependsOn(buildJni) }
```

**Issues:**
1. The code is commented out but suggests it's "optional"
2. Developers trying to enable it might fail if cargo-ndk/NDK aren't set up
3. Mixed signals: is JNI build automated or manual?
4. No clear documentation on when this should be enabled

**Required Fix:**

Either fully enable it (with docs) or fully remove it:

**Option A: Enable it (recommended for single-machine dev)**
```kotlin
val buildJni = tasks.register<Exec>("buildJni") {
    description = "Build libradar.so (requires: rustup, cargo-ndk, ANDROID_NDK_HOME)"
    
    commandLine("bash", "scripts/build_jni.sh")
    workingDir = rootProject.projectDir
    
    // Fail loudly if prerequisites missing
    doFirst {
        val ndkHome = System.getenv("ANDROID_NDK_HOME")
        if (ndkHome == null || ndkHome.isEmpty()) {
            throw GradleException("""
                ANDROID_NDK_HOME is not set.
                Please install Android NDK and run:
                  export ANDROID_NDK_HOME=/path/to/android-ndk
                or set it in your ~/.bashrc or ~/.zshrc
            """.trimIndent())
        }
    }
}

// Only enable auto-build if explicitly requested
if (project.hasProperty("buildJni")) {
    tasks.named("preBuild") { dependsOn(buildJni) }
}
```

Then document in `README.md`:
```markdown
## Building with JNI (Optional Automation)

To automatically build `libradar.so` before the Android build:
```bash
./gradlew build -PbuildJni
```

By default, `libradar.so` must be pre-built:
```bash
bash scripts/build_jni.sh
./gradlew build
```
```

**Option B: Remove it entirely**
```kotlin
// Deleted — use scripts/build_jni.sh manually or rely on GitHub Actions
```

Then document in `README.md` to always run build_jni.sh first.

---

## Low-Severity Issues (Minor Improvements)

### 10. ℹ️ **MISSING ARTIFACT UPLOAD NAMING IN CI JOBS**
**Severity:** LOW  
**File:** `.github/workflows/ci.yml` (lines ~95-104, ~160-168)  
**Impact:** Test reports accumulate with generic names; hard to distinguish between jobs

**Problem:**
```yaml
- name: Upload test results
  uses: actions/upload-artifact@v4
  with:
    name: unit-test-results  # Generic name
    path: app/build/reports/tests/

- name: Upload instrumented test results
  uses: actions/upload-artifact@v4
  with:
    name: instrumented-test-results  # Generic name
    path: app/build/reports/androidTests/
```

**Issue:**
- Artifact names don't include run ID or timestamp
- Multiple runs overwrite each other
- Hard to find historical test results

**Recommended Fix:**
```yaml
- name: Upload test results
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: unit-test-results-${{ github.run_id }}-${{ github.run_attempt }}
    path: app/build/reports/tests/
    retention-days: 30

- name: Upload instrumented test results
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: instrumented-test-results-${{ github.run_id }}-${{ github.run_attempt }}
    path: app/build/reports/androidTests/
    retention-days: 30
```

---

### 11. ℹ️ **NO LINT OR KTLINT STEP IN CI**
**Severity:** LOW  
**File:** `.github/workflows/ci.yml`  
**Impact:** Code style issues not caught in CI; inconsistent formatting in commits

**Problem:**
- No linting step for Kotlin code
- No code style checks
- Developers can commit code that violates style guides
- Code review burden increases

**Recommended Fix:**

Add a linting job:
```yaml
android-lint:
  name: Android — Lint
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: "17"
        distribution: "temurin"
    - name: Run Android Lint
      run: ./gradlew lint --stacktrace
    - name: Upload Lint Report
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: lint-report-${{ github.run_id }}
        path: app/build/reports/lint-results-*.html
```

Then update `gradle/libs.versions.toml` to include ktlint if needed.

---

### 12. ℹ️ **NO GRADLE VERSION OR DEPENDENCY CHECK**
**Severity:** LOW  
**File:** `.github/workflows/ci.yml`  
**Impact:** Gradle or dependency update failures not caught; dependency drift undetected

**Problem:**
- No `./gradlew dependencies` or `./gradlew dependencyUpdates` step
- No check for security vulnerabilities in dependencies
- Gradle version pinned in `gradle-wrapper.properties` but not checked

**Recommended Fix:**

```yaml
android-dependency-check:
  name: Android — Dependency Check
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: "17"
        distribution: "temurin"
    - name: Verify Gradle wrapper
      run: |
        echo "Gradle wrapper version:"
        ./gradlew --version
    - name: Check dependency resolution
      run: ./gradlew dependencies --stacktrace
```

---

## Summary Table

| # | Issue | Severity | Component | Impact | Fix Effort |
|---|-------|----------|-----------|--------|-----------|
| 1 | Missing APK build verification | **CRITICAL** | ci.yml | Can't verify app packages | High |
| 2 | Invalid placeholder libradar.so | **CRITICAL** | ci.yml | Instrumented tests crash | High |
| 3 | ARM64/x86_64 architecture mismatch | **CRITICAL** | ci.yml | Tests can't load native lib | Medium |
| 4 | No JNI build in CI | **HIGH** | ci.yml | Tests use invalid .so | Medium |
| 5 | cargo-ndk not verified | **HIGH** | build-jni.yml | Build fails silently | Low |
| 6 | NDK download not cached, no retry | **HIGH** | build-jni.yml | Fragile, slow, wasteful | Medium |
| 7 | Submodule init not verified | **MEDIUM** | build-jni.yml | Confusing error messages | Low |
| 8 | Git push may fail silently | **MEDIUM** | build-jni.yml | .so not committed | Medium |
| 9 | Commented-out JNI auto-build | **MEDIUM** | app/build.gradle.kts | Developer confusion | Low |
| 10 | Generic artifact names | **LOW** | ci.yml | Hard to find test results | Very Low |
| 11 | No lint/code style checks | **LOW** | ci.yml | Code quality not enforced | Very Low |
| 12 | No dependency version checks | **LOW** | ci.yml | Dependency drift undetected | Very Low |

---

## Recommended Implementation Priority

**Phase 1 (Immediate — Blocking Release):**
1. Add APK build verification
2. Fix architecture mismatch or skip instrumented tests
3. Replace placeholder .so with valid binary or skip tests
4. Integrate JNI build into CI

**Phase 2 (Next Sprint — Improves Reliability):**
5. Add NDK caching and retry logic
6. Verify submodule initialization
7. Fix git push error handling
8. Uncomment/document JNI auto-build in Gradle

**Phase 3 (Nice to Have):**
9. Add lint/code style checks
10. Add dependency vulnerability scanning
11. Improve artifact naming
12. Add test coverage reporting

---

## Testing the Fixes

After implementing the above fixes, verify with:

```bash
# Local dry-run of CI workflow
act -j rust-mayara-server-tests
act -j rust-jni-tests
act -j android-unit-tests
act -j android-instrumented-tests
act -j build-jni
act -j android-build-apk

# Or push to a test branch
git push origin fixes/ci-build-issues
# Then check GitHub Actions UI for all jobs passing
```

---

## References

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Android Gradle Build Documentation](https://developer.android.com/build)
- [Rust Android NDK](https://docs.rust-embedded.org/wg-embedded-graphics/)
- [cargo-ndk Documentation](https://github.com/bbqsrc/cargo-ndk)
