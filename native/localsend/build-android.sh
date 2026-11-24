#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CRATE="$ROOT/native/localsend"

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

NDK_ARGS=()
for abi in "${ABIS[@]}"; do
  NDK_ARGS+=(-t "$abi")
done

# cargo-ndk resolves workspace metadata from the current directory before
# forwarding Cargo arguments, so run it from the JNI crate itself.
cd "$CRATE"
cargo ndk "${NDK_ARGS[@]}" --platform 21 \
  -o "$ROOT/app/src/main/jniLibs" \
  build --release

echo "LocalSend native libraries installed under app/src/main/jniLibs/"
find "$ROOT/app/src/main/jniLibs" -name 'libaerial_localsend.so' -print
