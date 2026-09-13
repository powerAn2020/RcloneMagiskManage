#!/usr/bin/env bash
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET=${TARGET:-x86_64-linux-android}
OUT=${OUT:-"$ROOT/dist/rclone-manager-$TARGET"}

case "$TARGET" in
  aarch64-linux-android) ABI="arm64-v8a" ;;
  armv7-linux-androideabi) ABI="armeabi-v7a" ;;
  x86_64-linux-android) ABI="x86_64" ;;
  i686-linux-android) ABI="x86" ;;
  *) ABI="$TARGET" ;;
esac

# Detect Linux NDK in CI/CD environment or Windows NDK
if [ -d "${ANDROID_NDK_LATEST_HOME:-}" ]; then
  NDK_ROOT="$ANDROID_NDK_LATEST_HOME"
elif [ -d "${ANDROID_NDK_HOME:-}" ]; then
  NDK_ROOT="$ANDROID_NDK_HOME"
elif [ -d "${ANDROID_HOME:-}/ndk" ]; then
  NDK_ROOT=$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 | sort -V | tail -n 1)
else
  NDK_ROOT=${NDK_ROOT:-}
fi

if [ -d "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin" ]; then
  LLVM_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
  export PATH="$LLVM_BIN:$PATH"
  export CC_x86_64_linux_android="$LLVM_BIN/x86_64-linux-android34-clang"
  export CXX_x86_64_linux_android="$LLVM_BIN/x86_64-linux-android34-clang++"
  export AR_x86_64_linux_android="$LLVM_BIN/llvm-ar"
  export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$LLVM_BIN/x86_64-linux-android34-clang"
  export CARGO_TARGET_X86_64_LINUX_ANDROID_AR="$LLVM_BIN/llvm-ar"
  export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS=""

  export CC_aarch64_linux_android="$LLVM_BIN/aarch64-linux-android34-clang"
  export CXX_aarch64_linux_android="$LLVM_BIN/aarch64-linux-android34-clang++"
  export AR_aarch64_linux_android="$LLVM_BIN/llvm-ar"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LLVM_BIN/aarch64-linux-android34-clang"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$LLVM_BIN/llvm-ar"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS=""
elif [ -x "$NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe" ]; then
  LLVM_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin"
  export PATH="$LLVM_BIN:$PATH"
  export CC_x86_64_linux_android="$LLVM_BIN/x86_64-linux-android34-clang.cmd"
  export CXX_x86_64_linux_android="$LLVM_BIN/x86_64-linux-android34-clang++.cmd"
  export AR_x86_64_linux_android="$LLVM_BIN/llvm-ar.exe"
  export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$LLVM_BIN/x86_64-linux-android34-clang.cmd"
  export CC_aarch64_linux_android="$LLVM_BIN/aarch64-linux-android34-clang.cmd"
  export CXX_aarch64_linux_android="$LLVM_BIN/aarch64-linux-android34-clang++.cmd"
  export AR_aarch64_linux_android="$LLVM_BIN/llvm-ar.exe"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LLVM_BIN/aarch64-linux-android34-clang.cmd"
fi
cargo build --release --target "$TARGET" -p rclone-gateway

rm -rf "$OUT"
mkdir -p "$OUT/bin"

# 1. Copy Gateway binary
cp "$ROOT/target/$TARGET/release/rclone-gateway" "$OUT/bin/rclone-gateway"

# 2. Copy integrated rclone and fusermount3 binaries (All-in-One, no external module)
PREBUILT_DIR="$ROOT/magisk-module/prebuilt/$ABI"
SUBMODULE_BIN_DIR="$ROOT/external/rclone-fuse3-magisk/magisk-rclone_$ABI/system/vendor/bin"

if [ -f "$PREBUILT_DIR/rclone" ] && [ -f "$PREBUILT_DIR/fusermount3" ]; then
  echo "Integrating prebuilt binaries from $PREBUILT_DIR..."
  cp "$PREBUILT_DIR/rclone" "$OUT/bin/rclone"
  cp "$PREBUILT_DIR/fusermount3" "$OUT/bin/fusermount3"
elif [ -f "$SUBMODULE_BIN_DIR/rclone" ] && [ -f "$SUBMODULE_BIN_DIR/fusermount3" ]; then
  echo "Integrating binaries from submodule build $SUBMODULE_BIN_DIR..."
  cp "$SUBMODULE_BIN_DIR/rclone" "$OUT/bin/rclone"
  cp "$SUBMODULE_BIN_DIR/fusermount3" "$OUT/bin/fusermount3"
else
  echo "⚠️ Warning: rclone and fusermount3 not found for $ABI (looked in prebuilt and submodule)"
fi

# 3. Copy module scripts (Pure service module, no system/ directory)
cp "$ROOT/magisk-module/module.prop" "$ROOT/magisk-module/service.sh" "$ROOT/magisk-module/uninstall.sh" "$OUT/"
cp "$ROOT/magisk-module/post-fs-data.sh" "$ROOT/magisk-module/customize.sh" "$OUT/"
if [ -d "$ROOT/magisk-module/META-INF" ]; then
  cp -r "$ROOT/magisk-module/META-INF" "$OUT/"
fi

# 4. Integrate Android companion APK (installed via customize.sh)
APK_SOURCE=""
if [ -f "$ROOT/app/build/outputs/apk/release/app-release.apk" ]; then
  APK_SOURCE="$ROOT/app/build/outputs/apk/release/app-release.apk"
elif [ -f "$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
  APK_SOURCE="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
elif [ -f "$ROOT/app/build/outputs/apk/debug/app-debug.apk" ]; then
  APK_SOURCE="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
fi

if [ -n "$APK_SOURCE" ]; then
  echo "Integrating companion APK: $APK_SOURCE -> $OUT/app.apk"
  cp "$APK_SOURCE" "$OUT/app.apk"
else
  echo "⚠️ Warning: No companion APK found in app/build/outputs/apk/, packaging without app.apk"
fi


# 5. Enforce: No system/ directory (No system mount overlay)
rm -rf "$OUT/system"

# 6. Set executable permissions
chmod 0755 "$OUT/bin/"* "$OUT/"*.sh
[ -d "$OUT/META-INF" ] && chmod -R 0755 "$OUT/META-INF"

# 7. Package flashable ZIP for Magisk / KernelSU / APatch
mkdir -p "$ROOT/dist"
ZIP_OUT="$ROOT/dist/rclone-manager-$TARGET.zip"
rm -f "$ZIP_OUT"
if command -v zip >/dev/null 2>&1; then
  (cd "$OUT" && zip -r -q "$ZIP_OUT" *)
elif command -v python3 >/dev/null 2>&1; then
  python3 -c "import os, zipfile; zf = zipfile.ZipFile('$ZIP_OUT', 'w', zipfile.ZIP_DEFLATED); [zf.write(os.path.join(r, f), os.path.relpath(os.path.join(r, f), '$OUT')) for r, _, fs in os.walk('$OUT') for f in fs]; zf.close()"
elif command -v tar >/dev/null 2>&1; then
  (cd "$OUT" && tar -a -c -f "$ZIP_OUT" *)
fi


# Also create concise alias ZIP (e.g., rclone-manager-arm64.zip, rclone-manager-x86_64.zip)
ALIAS_NAME=""
case "$ABI" in
  arm64-v8a) ALIAS_NAME="rclone-manager-arm64.zip" ;;
  x86_64)    ALIAS_NAME="rclone-manager-x86_64.zip" ;;
esac
if [ -n "$ALIAS_NAME" ]; then
  cp "$ZIP_OUT" "$ROOT/dist/$ALIAS_NAME"
fi

printf 'Module directory: %s\n' "$OUT"
printf 'Flashable ZIP:    %s\n' "$ZIP_OUT"
[ -n "$ALIAS_NAME" ] && printf 'Alias ZIP:        %s\n' "$ROOT/dist/$ALIAS_NAME"
