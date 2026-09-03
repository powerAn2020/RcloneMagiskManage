#!/system/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET=${TARGET:-x86_64-linux-android}
OUT=${OUT:-"$ROOT/dist/rclone-manager-$TARGET"}
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
cp "$ROOT/target/$TARGET/release/rclone-gateway" "$OUT/bin/rclone-gateway"
cp "$ROOT/magisk-module/module.prop" "$ROOT/magisk-module/service.sh" "$ROOT/magisk-module/uninstall.sh" "$OUT/"
cp "$ROOT/magisk-module/post-fs-data.sh" "$OUT/"
chmod 0755 "$OUT/bin/rclone-gateway" "$OUT/service.sh" "$OUT/post-fs-data.sh" "$OUT/uninstall.sh"
printf '%s\n' "$OUT"
