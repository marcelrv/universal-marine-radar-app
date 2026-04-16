#!/usr/bin/env bash
# update_mayara.sh — Pull upstream mayara-server changes into the fork submodule and rebuild.
#
# This script:
#   1. Fetches the upstream (original MarineYachtRadar/mayara-server) into the fork.
#   2. Merges into the fork's main branch.
#   3. Rebuilds libradar.so.
#
# One-time setup (run once after forking):
#   cd mayara-server
#   git remote add upstream https://github.com/MarineYachtRadar/mayara-server.git
#   cd ..
#
# Usage:
#   bash scripts/update_mayara.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SUBMODULE_DIR="${REPO_ROOT}/mayara-server"

if [[ ! -d "${SUBMODULE_DIR}/.git" ]] && [[ ! -f "${SUBMODULE_DIR}/.git" ]]; then
    echo "ERROR: mayara-server submodule not initialised." >&2
    echo "       Run: git submodule update --init --recursive" >&2
    exit 1
fi

cd "${SUBMODULE_DIR}"

# Verify upstream remote exists
if ! git remote get-url upstream &>/dev/null; then
    echo "ERROR: 'upstream' remote not configured in mayara-server." >&2
    echo "       Run inside mayara-server/:" >&2
    echo "       git remote add upstream https://github.com/MarineYachtRadar/mayara-server.git" >&2
    exit 1
fi

# Guard: warn if there are uncommitted changes (they would be overwritten by merge)
if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "WARNING: Uncommitted changes detected in mayara-server submodule."
    read -rp "Continue anyway? [y/N] " confirm
    if [[ "${confirm}" != "y" && "${confirm}" != "Y" ]]; then
        echo "Aborted."
        exit 0
    fi
fi

echo "==> Fetching upstream changes..."
git fetch upstream

echo "==> Merging upstream/main into current branch..."
git merge upstream/main --no-edit

echo "==> Running upstream tests to verify merge..."
cargo test --features emulator 2>&1 | tail -20

echo "==> Rebuilding libradar.so..."
cd "${REPO_ROOT}"
bash scripts/build_jni.sh

echo ""
echo "==> mayara-server updated and libradar.so rebuilt successfully."
echo "    Commit the updated submodule pointer:"
echo "    git add mayara-server && git commit -m 'chore: update mayara-server submodule'"
