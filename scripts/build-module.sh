#!/system/bin/sh
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

# Make the documented Windows/NDK environment reproducible when this script is
# invoked directly from Git Bash or PowerShell. Callers can override NDK_ROOT.
NDK_ROOT=${NDK_ROOT:-C:/Development/JetBrains/AndroidSDK/ndk/26.3.11579264}
LLVM_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin"
if [ -x "$LLVM_BIN/clang.exe" ]; then
  export CC_x86_64_linux_android=${CC_x86_64_linux_android:-"$LLVM_BIN/clang.exe"}
  export CXX_x86_64_linux_android=${CXX_x86_64_linux_android:-"$LLVM_BIN/clang++.exe"}
  export AR_x86_64_linux_android=${AR_x86_64_linux_android:-"$LLVM_BIN/llvm-ar.exe"}
fi
cargo build --release --target "$TARGET" -p rclone-gateway
rm -rf "$OUT"
mkdir -p "$OUT/bin"

# 1. Copy Gateway binary
cp "$ROOT/target/$TARGET/release/rclone-gateway" "$OUT/bin/rclone-gateway"

# 2. Copy integrated rclone and fusermount3 binaries (All-in-One, no external module)
PREBUILT_DIR="$ROOT/magisk-module/prebuilt/$ABI"
if [ -d "$PREBUILT_DIR" ]; then
  echo "Integrating prebuilt binaries from $PREBUILT_DIR..."
  [ -f "$PREBUILT_DIR/rclone" ] && cp "$PREBUILT_DIR/rclone" "$OUT/bin/rclone"
  [ -f "$PREBUILT_DIR/fusermount3" ] && cp "$PREBUILT_DIR/fusermount3" "$OUT/bin/fusermount3"
elif [ -f "$ROOT/scratch/unpacked_magisk_rclone/system/vendor/bin/rclone" ] && [ "$ABI" = "x86_64" ]; then
  echo "Integrating prebuilt binaries from scratch cache..."
  cp "$ROOT/scratch/unpacked_magisk_rclone/system/vendor/bin/rclone" "$OUT/bin/rclone"
  cp "$ROOT/scratch/unpacked_magisk_rclone/system/vendor/bin/fusermount3" "$OUT/bin/fusermount3"
fi

# 3. Copy module scripts (Pure service module, no system/ directory)
cp "$ROOT/magisk-module/module.prop" "$ROOT/magisk-module/service.sh" "$ROOT/magisk-module/uninstall.sh" "$OUT/"
cp "$ROOT/magisk-module/post-fs-data.sh" "$ROOT/magisk-module/customize.sh" "$OUT/"
if [ -d "$ROOT/magisk-module/META-INF" ]; then
  cp -r "$ROOT/magisk-module/META-INF" "$OUT/"
fi

# 4. Enforce: No system/ directory (No system mount overlay)
rm -rf "$OUT/system"

# 5. Set executable permissions
chmod 0755 "$OUT/bin/"* "$OUT/"*.sh
[ -d "$OUT/META-INF" ] && chmod -R 0755 "$OUT/META-INF"

# 6. Package flashable ZIP for Magisk / KernelSU / APatch
ZIP_OUT="$ROOT/dist/rclone-manager-$TARGET.zip"
rm -f "$ZIP_OUT"
if command -v zip >/dev/null 2>&1; then
  (cd "$OUT" && zip -r -q "$ZIP_OUT" .)
elif command -v tar >/dev/null 2>&1; then
  tar -a -c -f "$ZIP_OUT" -C "$OUT" .
elif command -v powershell.exe >/dev/null 2>&1; then
  powershell.exe -Command "Compress-Archive -Path '$OUT/*' -DestinationPath '$ZIP_OUT' -Force"
fi

printf 'Module directory: %s\n' "$OUT"
printf 'Flashable ZIP:    %s\n' "$ZIP_OUT"
