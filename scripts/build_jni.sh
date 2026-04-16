#!/usr/bin/env bash
# build_jni.sh — Cross-compile mayara-jni to arm64-v8a and copy libradar.so into the app.
#
# Prerequisites:
#   rustup target add aarch64-linux-android
#   cargo install cargo-ndk
#   ANDROID_NDK_HOME must be set (e.g. ~/Android/Sdk/ndk/26.3.11579264)
#
# Usage:
#   bash scripts/build_jni.sh             # release build (default)
#   BUILD_TYPE=debug bash scripts/build_jni.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

BUILD_TYPE="${BUILD_TYPE:-release}"
TARGET="aarch64-linux-android"
JNI_DIR="${REPO_ROOT}/app/src/main/jniLibs/arm64-v8a"

echo "==> Building mayara-jni (${BUILD_TYPE}) for ${TARGET}"

# Check prerequisites
if ! command -v cargo-ndk &> /dev/null; then
    echo "ERROR: cargo-ndk not found. Run: cargo install cargo-ndk" >&2
    exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    echo "ERROR: ANDROID_NDK_HOME is not set." >&2
    echo "       Set it to your NDK path, e.g.:" >&2
    echo "       export ANDROID_NDK_HOME=~/Android/Sdk/ndk/26.3.11579264" >&2
    exit 1
fi

if [[ ! -d "${REPO_ROOT}/mayara-server" ]] || [[ -z "$(ls -A "${REPO_ROOT}/mayara-server")" ]]; then
    echo "ERROR: mayara-server submodule not initialised." >&2
    echo "       Run: git submodule update --init --recursive" >&2
    exit 1
fi

# Build
cd "${REPO_ROOT}/mayara-jni"

if [[ "${BUILD_TYPE}" == "release" ]]; then
    cargo ndk --target "${TARGET}" --platform 26 -- build --release
    SO_PATH="${REPO_ROOT}/mayara-jni/target/${TARGET}/release/libradar.so"
else
    cargo ndk --target "${TARGET}" --platform 26 -- build
    SO_PATH="${REPO_ROOT}/mayara-jni/target/${TARGET}/debug/libradar.so"
fi

# Copy into jniLibs
mkdir -p "${JNI_DIR}"
cp "${SO_PATH}" "${JNI_DIR}/libradar.so"

SO_SIZE=$(du -h "${JNI_DIR}/libradar.so" | cut -f1)
echo "==> libradar.so copied to ${JNI_DIR} (${SO_SIZE})"
echo "==> Done. Open Android Studio and sync/rebuild the app."
