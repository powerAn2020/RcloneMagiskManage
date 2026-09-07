# Setup toolchain environment variables for RcloneMagiskManage
# Usage: . .\scripts\setup-env.ps1

$env:JAVA_HOME = "C:\Development\Java\jdk-17.0.12"
$env:ANDROID_HOME = "C:\Development\JetBrains\AndroidSDK"
$env:ANDROID_SDK_ROOT = "C:\Development\JetBrains\AndroidSDK"
$env:NDK_ROOT = "C:\Development\JetBrains\AndroidSDK\ndk\26.3.11579264"

# NDK LLVM toolchain for Rust Android cross-compilation
$llvm = "$env:NDK_ROOT\toolchains\llvm\prebuilt\windows-x86_64\bin"
$env:CC_x86_64_linux_android = "$llvm\x86_64-linux-android34-clang.cmd"
$env:CXX_x86_64_linux_android = "$llvm\x86_64-linux-android34-clang++.cmd"
$env:AR_x86_64_linux_android = "$llvm\llvm-ar.exe"

# Prepend platform-tools (adb) and Java 17 to PATH
$env:PATH = "C:\Development\platform-tools;$env:JAVA_HOME\bin;$env:PATH"

Write-Host "Toolchain environment variables configured:" -ForegroundColor Green
Write-Host "  JAVA_HOME: $env:JAVA_HOME"
Write-Host "  ANDROID_HOME: $env:ANDROID_HOME"
Write-Host "  NDK_ROOT: $env:NDK_ROOT"
Write-Host "  ADB: $(Get-Command adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)"
Write-Host "  JAVA: $(Get-Command java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)"
