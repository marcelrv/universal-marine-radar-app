#!/usr/bin/env bash
# copy_so.sh — Copy the most recently built libradar.so into jniLibs.
#
# Use this when you've built mayara-jni manually with cargo and want to
# copy the output without re-running the full build.
#
# Usage:
#   BUILD_TYPE=debug bash scripts/copy_so.sh    # debug build
#   bash scripts/copy_so.sh                     # release build (default)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

BUILD_TYPE="${BUILD_TYPE:-release}"
TARGET="aarch64-linux-android"
JNI_DIR="${REPO_ROOT}/app/src/main/jniLibs/arm64-v8a"

SO_PATH="${REPO_ROOT}/mayara-jni/target/${TARGET}/${BUILD_TYPE}/libradar.so"

if [[ ! -f "${SO_PATH}" ]]; then
    echo "ERROR: ${SO_PATH} not found." >&2
    echo "       Run scripts/build_jni.sh first." >&2
    exit 1
fi

mkdir -p "${JNI_DIR}"
cp "${SO_PATH}" "${JNI_DIR}/libradar.so"

SO_SIZE=$(du -h "${JNI_DIR}/libradar.so" | cut -f1)
echo "==> Copied ${SO_SIZE} libradar.so to ${JNI_DIR}"
