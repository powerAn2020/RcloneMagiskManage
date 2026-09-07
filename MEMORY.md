# RcloneMagiskManage - Project Memory

## Tech Stack
- **Android App**: Kotlin, Jetpack Compose, Material 3, Coroutines, libsu (Root IPC)
- **Privileged Backend**: Rust (Axum, Tokio, Rusqlite, ChaCha20Poly1305), target `x86_64-linux-android`
- **Module Format**: Magisk / KernelSU / APatch module (`/data/adb/modules/rclone-manager/`)

## Environment Variables & Toolchain (CRITICAL)

**ALWAYS** use the following toolchain paths on this Windows system:

- **JAVA_HOME**: `C:\Development\Java\jdk-17.0.12`
  - **CRITICAL**: System default is Java 8 (`C:\Development\Java\jdk8`), which causes Gradle to fail (`JVM 17 or later required`).
  - Configured in `gradle.properties` via `org.gradle.java.home=C:\\Development\\Java\\jdk-17.0.12`.
- **ANDROID_HOME** / **sdk.dir**: `C:\Development\JetBrains\AndroidSDK` (configured in `local.properties`)
- **NDK_ROOT**: `C:\Development\JetBrains\AndroidSDK\ndk\26.3.11579264`
- **Rust Android LLVM**:
  - `CC_x86_64_linux_android` = `C:/Development/JetBrains/AndroidSDK/ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe`
  - `CXX_x86_64_linux_android` = `C:/Development/JetBrains/AndroidSDK/ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe`
  - `AR_x86_64_linux_android` = `C:/Development/JetBrains/AndroidSDK/ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-ar.exe`
- **ADB / Platform Tools**: `C:\Development\platform-tools\adb.exe`
- **Node.js**: `C:\Development\nodejs`
- **Python**: `C:\Development\Python`
- **Maven**: `C:\Development\apache-maven-3.9.8`

## Commands

- **One-click Shell Env Setup**: `. .\scripts\setup-env.ps1`
- **Build Gateway Binary**:
  ```powershell
  $env:NDK_ROOT = "C:/Development/JetBrains/AndroidSDK/ndk/26.3.11579264"; $llvm = "$env:NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin"; $env:CC_x86_64_linux_android = "$llvm/clang.exe"; $env:CXX_x86_64_linux_android = "$llvm/clang++.exe"; $env:AR_x86_64_linux_android = "$llvm/llvm-ar.exe"; cargo build --release --target x86_64-linux-android -p rclone-gateway
  ```
- **Deploy Gateway to Emulator**:
  ```powershell
  $OUT = "dist/rclone-manager-x86_64-linux-android"; Copy-Item "target/x86_64-linux-android/release/rclone-gateway" "$OUT/bin/rclone-gateway" -Force; powershell -File scripts/deploy-module.ps1 -RestartService
  ```
- **Build & Install Android App**:
  ```powershell
  ./gradlew installDebug
  ```
- **Cargo Unit Tests**:
  ```powershell
  cargo test --manifest-path gateway/Cargo.toml
  ```

## Critical Architecture & Conventions

1. **Magisk Module & State Paths**:
   - Module files: `/data/adb/modules/rclone-manager/`
   - Gateway binary: `/data/adb/modules/rclone-manager/bin/rclone-gateway`
   - State & Database: `/data/adb/rclone-manage/db/state.db`
   - Socket: `/data/adb/rclone-manage/runtime/gateway.sock`
   - Logs: `/data/adb/rclone-manage/logs/`
2. **Gateway IPC Client**:
   - `libsu` command execution requires capturing `stderr` via `.to(outList, errList)` so errors are not silenced.
   - `request_cli` socket timeout is set to 35s to allow remote operations to finish.
3. **Rclone Execution**:
   - Always use `--contimeout 5s --timeout 8s --retries 1 --low-level-retries 1` for network probes (`remote_test`) to fail fast and report accurate diagnostics.
   - Temporary configs must use `TempConfig` RAII guard to prevent leftover `rclone-*.conf` files in runtime.
