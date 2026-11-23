#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "cargo-ndk is required. Install with: cargo install cargo-ndk --locked" >&2
  exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}${ANDROID_NDK_ROOT:-}" ]]; then
  echo "Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT to an installed Android NDK." >&2
  exit 1
fi

export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_NDK_ROOT}"
ABIS=(arm64-v8a armeabi-v7a x86_64)
if [[ $# -gt 0 ]]; then
  ABIS=("$@")
fi

cargo ndk \
  "${ABIS[@]/#/-t }" \
  --platform 21 \
  -o app/src/main/jniLibs \
  build --release --manifest-path native/localsend/Cargo.toml

echo "LocalSend native libraries installed under app/src/main/jniLibs/"
find app/src/main/jniLibs -name 'libaerial_localsend.so' -print
